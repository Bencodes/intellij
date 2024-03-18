/*
 * Copyright 2022 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.qsync;

import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.idea.blaze.base.bazel.BazelExitCode;
import com.google.idea.blaze.base.logging.utils.querysync.BuildDepsStatsScope;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.common.Label;
import com.google.idea.blaze.common.PrintOutput;
import com.google.idea.blaze.exception.BuildException;
import com.google.idea.blaze.qsync.BlazeProject;
import com.google.idea.blaze.qsync.BlazeProjectSnapshot;
import com.google.idea.blaze.qsync.deps.ArtifactTracker;
import com.google.idea.blaze.qsync.deps.OutputInfo;
import com.google.idea.blaze.qsync.project.DependencyTrackingBehavior;
import com.google.idea.blaze.qsync.project.ProjectDefinition;
import com.google.idea.blaze.qsync.project.RequestedTargets;
import com.intellij.openapi.util.text.StringUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * A file that tracks what files in the project can be analyzed and what is the status of their
 * dependencies.
 *
 * <p>The dependencies tracked for a target depends on its {@link DependencyTrackingBehavior}, which
 * is in turn determined by the source code language of the target.
 */
public class DependencyTrackerImpl implements DependencyTracker {

  private final BlazeProject blazeProject;
  private final DependencyBuilder builder;
  private final ArtifactTracker<BlazeContext> artifactTracker;

  public DependencyTrackerImpl(
      BlazeProject blazeProject,
      DependencyBuilder builder,
      ArtifactTracker<BlazeContext> artifactTracker) {
    this.blazeProject = blazeProject;
    this.builder = builder;
    this.artifactTracker = artifactTracker;
  }

  /**
   * For a given project targets, returns all the targets outside the project that its source files
   * need to be edited fully. This method return the dependencies for the target with fewest pending
   * so that if dependencies have been built for one, the empty set will be returned even if others
   * have pending dependencies.
   */
  @Override
  public Set<Label> getPendingExternalDeps(Set<Label> projectTargets) {
    BlazeProjectSnapshot currentSnapshot = blazeProject.getCurrent().orElse(null);
    if (currentSnapshot == null) {
      return ImmutableSet.of();
    }

    Set<Label> cachedTargets = artifactTracker.getLiveCachedTargets();
    return projectTargets.stream()
        .map(projectTarget -> currentSnapshot.graph().getExternalDepsToBuildFor(projectTarget))
        .map(targets -> Sets.difference(targets, cachedTargets))
        .min(Comparator.comparingInt(SetView::size))
        .map(SetView::immutableCopy)
        .orElse(ImmutableSet.of());
  }

  /** Recursively get all the transitive deps outside the project */
  @Override
  public Set<Label> getPendingTargets(Path workspaceRelativePath) {
    Preconditions.checkState(!workspaceRelativePath.isAbsolute(), workspaceRelativePath);

    Optional<BlazeProjectSnapshot> currentSnapshot = blazeProject.getCurrent();
    if (currentSnapshot.isEmpty()) {
      return ImmutableSet.of();
    }
    ImmutableSet<Label> owners = currentSnapshot.get().getTargetOwners(workspaceRelativePath);
    if (owners == null) {
      return ImmutableSet.of();
    }
    return getPendingExternalDeps(owners);
  }

  private BlazeProjectSnapshot getCurrentSnapshot() {
    return blazeProject
        .getCurrent()
        .orElseThrow(() -> new IllegalStateException("Sync is not yet complete"));
  }

  /**
   * Builds the external dependencies of the given targets, putting the resultant libraries in the
   * shared library directory so that they are picked up by the IDE.
   */
  @Override
  public boolean buildDependenciesForTargets(BlazeContext context, DependencyBuildRequest request)
      throws IOException, BuildException {
    BuildDepsStatsScope.fromContext(context)
        .ifPresent(stats -> stats.setRequestedTargets(request.targets));
    BlazeProjectSnapshot snapshot = getCurrentSnapshot();

    Optional<RequestedTargets> maybeRequestedTargets = getRequestedTargets(snapshot, request);
    if (maybeRequestedTargets.isEmpty()) {
      return false;
    }

    buildDependencies(context, snapshot, maybeRequestedTargets.get());
    return true;
  }

  private Optional<RequestedTargets> getRequestedTargets(
      BlazeProjectSnapshot snapshot, DependencyBuildRequest request) {
    switch (request.requestType) {
      case MULTIPLE_TARGETS:
        return snapshot.graph().computeRequestedTargets(request.targets);
      case SINGLE_TARGET:
        return Optional.of(new RequestedTargets(request.targets, request.targets));
      case WHOLE_PROJECT:
        return Optional.of(
            new RequestedTargets(
                snapshot.graph().allTargets().toLabelSet(), snapshot.graph().projectDeps()));
    }
    throw new IllegalArgumentException("Invalid request type: " + request.requestType);
  }

  private void buildDependencies(
      BlazeContext context, BlazeProjectSnapshot snapshot, RequestedTargets requestedTargets)
      throws IOException, BuildException {
    BuildDepsStatsScope.fromContext(context)
        .ifPresent(stats -> stats.setBuildTargets(requestedTargets.buildTargets));
    OutputInfo outputInfo =
        builder.build(
            context,
            requestedTargets.buildTargets,
            snapshot.graph().getTargetLanguages(requestedTargets.buildTargets));
    reportErrorsAndWarnings(context, snapshot, outputInfo);

    artifactTracker.update(requestedTargets.expectedDependencyTargets, outputInfo, context);
  }

  private void reportErrorsAndWarnings(
      BlazeContext context, BlazeProjectSnapshot snapshot, OutputInfo outputInfo)
      throws NoDependenciesBuiltException {
    if (outputInfo.isEmpty()) {
      throw new NoDependenciesBuiltException(
          "Build produced no usable outputs. Please fix any build errors and retry. If you"
              + " observe 'no such target' errors, your project may be out of sync. Please sync"
              + " the project and retry.");
    }

    if (!outputInfo.getTargetsWithErrors().isEmpty()) {
      ProjectDefinition projectDefinition = snapshot.queryData().projectDefinition();
      context.setHasWarnings();
      ImmutableListMultimap<Boolean, Label> targetsByInclusion =
          Multimaps.index(outputInfo.getTargetsWithErrors(), projectDefinition::isIncluded);
      if (targetsByInclusion.containsKey(false)) {
        ImmutableList<?> errorTargets = targetsByInclusion.get(false);
        context.output(
            PrintOutput.error(
                "%d external %s had build errors: \n  %s",
                errorTargets.size(),
                StringUtil.pluralize("dependency", errorTargets.size()),
                errorTargets.stream().limit(10).map(Object::toString).collect(joining("\n  "))));
        if (errorTargets.size() > 10) {
          context.output(PrintOutput.log("and %d more.", errorTargets.size() - 10));
        }
      }
      if (targetsByInclusion.containsKey(true)) {
        ImmutableList<?> errorTargets = targetsByInclusion.get(true);
        context.output(
            PrintOutput.output(
                "%d project %s had build errors: \n  %s",
                errorTargets.size(),
                StringUtil.pluralize("target", errorTargets.size()),
                errorTargets.stream().limit(10).map(Object::toString).collect(joining("\n  "))));
        if (errorTargets.size() > 10) {
          context.output(PrintOutput.log("and %d more.", errorTargets.size() - 10));
        }
      }
    } else if (outputInfo.getExitCode() != BazelExitCode.SUCCESS) {
      // This will happen if there is an error in a build file, as no build actions are attempted
      // in that case.
      context.setHasWarnings();
      context.output(PrintOutput.error("There were build errors."));
    }
    if (context.hasWarnings()) {
      context.output(
          PrintOutput.error(
              "Your dependencies may be incomplete. If you see unresolved symbols, please fix the"
                  + " above build errors and try again."));
      context.setHasWarnings();
    }
  }

  /**
   * Returns a list of local cache files that build by target provided. Returns Optional.empty() if
   * the target has not yet been built.
   */
  @Override
  public Optional<ImmutableSet<Path>> getCachedArtifacts(Label target) {
    return artifactTracker.getCachedFiles(target);
  }
}
