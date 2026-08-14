// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.ancho;

import java.lang.StackWalker.StackFrame;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the gitoids of caller classes via {@link StackWalker} (JDK 9+).
 *
 * <p>Isolated in its own class for two reasons: (1) it references {@code StackWalker}, a
 * JDK-9 type, so it must only be loaded on JDK 9+ — {@link ProbeAdvice} guards every call with
 * a runtime version check, and the JVM resolves this class lazily, so on JDK 8 it is never
 * loaded; (2) its lambdas/streams stay out of {@code ProbeAdvice.onEnter}, which ByteBuddy
 * inlines into the probed method and cannot inline {@code invokedynamic}.
 *
 * <p>Lives on the bootstrap classloader (see {@link BootstrapInjector}) alongside the advice
 * and {@link ClassGitoidRegistry}.
 */
public final class CallerGitoidWalker {

    /** Bound the attached set so a deep stack can't bloat the event. */
    private static final int MAX_CALLERS = 16;

    /** Bound the frames examined so a very deep stack can't make one probe event expensive. */
    private static final int MAX_FRAMES = 64;

    /** StackWalker instances are immutable and thread-safe — create once, not per event. */
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private CallerGitoidWalker() {
    }

    /**
     * Newline-delimited gitoids of the caller classes on the current stack — excluding the
     * probed class itself and the agent's own frames — or null if none are in the registry.
     */
    public static String resolve(Class<?> declaringClass) {
        Set<String> gitoids = WALKER.walk(frames -> frames
                .limit(MAX_FRAMES)
                .map(StackFrame::getDeclaringClass)
                .filter(c -> c != declaringClass)
                .filter(c -> !c.getName().startsWith("io.spicelabs.ancho"))
                .map(c -> ClassGitoidRegistry.lookup(c.getClassLoader(), c.getName()))
                .filter(g -> g != null)
                .distinct()
                .limit(MAX_CALLERS)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return gitoids.isEmpty() ? null : String.join("\n", gitoids);
    }
}
