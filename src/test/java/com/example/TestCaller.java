// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package com.example;

import io.spicelabs.ancho.CallerGitoidWalker;

/**
 * Test helper in a non-{@code io.spicelabs.ancho} package, so its frames are treated as real
 * callers (the walker filters out the agent's own package).
 */
public final class TestCaller {

    private TestCaller() {
    }

    public static String callResolve(Class<?> declaringClass) {
        return CallerGitoidWalker.resolve(declaringClass);
    }
}
