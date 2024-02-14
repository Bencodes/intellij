/*
 * Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.common.Label;
import com.google.idea.blaze.exception.BuildException;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/** A local cache of built render jarss. */
public interface RenderJarArtifactTracker {

  /**
   * Fetches, caches and sets up new render jar artifacts.
   *
   * @return The set of files that were updated.
   */
  ImmutableSet<Path> update(Set<Label> targets, RenderJarInfo renderJarInfo, BlazeContext context)
      throws BuildException;

  /** Returns the list of render jars */
  ImmutableList<File> getRenderJars();
}
