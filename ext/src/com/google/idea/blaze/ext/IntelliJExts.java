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
package com.google.idea.blaze.ext;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Static utility methods relating to {@link IntelliJExtClient} and {@link IntelliJExtTestServer}.
 * Provides platform-specific implementations to get ManagedChannel using the netty library for
 * Linux.
 */
public final class IntelliJExts {
  public static EventLoopGroup createGroup(DefaultThreadFactory threadFactory) {
    return new EpollEventLoopGroup(threadFactory);
  }

  public static Class<? extends ServerChannel> getChannelType() {
    return EpollServerDomainSocketChannel.class;
  }

  private IntelliJExts() {}
}
