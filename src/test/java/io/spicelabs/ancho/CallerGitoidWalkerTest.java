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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.TestCaller;

/**
 * Tests for {@link CallerGitoidWalker} (runs on the JDK 9+ build JVM). Callers are exercised
 * via {@link TestCaller} (in {@code com.example}) because the walker filters out the agent's
 * own {@code io.spicelabs.ancho} frames.
 */
class CallerGitoidWalkerTest {

    @Test
    void resolve_returnsRegisteredCallerGitoids() {
        ClassLoader loader = TestCaller.class.getClassLoader();
        ClassGitoidRegistry.register(loader, "com.example.TestCaller", "gitoid:blob:sha256:caller");

        // TestCaller.callResolve is a caller frame; declaringClass is unrelated.
        String result = TestCaller.callResolve(String.class);

        assertNotNull(result, "should find TestCaller as a caller");
        List<String> gitoids = Arrays.asList(result.split("\n"));
        assertTrue(gitoids.contains("gitoid:blob:sha256:caller"),
                "caller gitoids should include the registered TestCaller gitoid");
    }

    @Test
    void resolve_excludesDeclaringClass() {
        ClassLoader loader = TestCaller.class.getClassLoader();
        ClassGitoidRegistry.register(loader, "com.example.TestCaller", "gitoid:blob:sha256:self");

        // declaringClass == TestCaller → must be filtered out even though it's a caller frame.
        String result = TestCaller.callResolve(TestCaller.class);

        if (result != null) {
            assertFalse(Arrays.asList(result.split("\n")).contains("gitoid:blob:sha256:self"),
                    "the declaring class must not appear in its own caller list");
        }
    }
}
