// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. \& Contributors

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

import org.junit.jupiter.api.Test;

class SpiceAgentTest {

    @Test
    void isJfrAvailable_onModernJdk() {
        // This test runs on the build JDK (11+) so JFR should be available
        assertTrue(SpiceAgent.isJfrAvailable(),
                "jdk.jfr.Event should be available on the build JDK");
    }

    @Test
    void premain_nullArgs_doesNotThrow() {
        // Should log a warning and return gracefully
        assertDoesNotThrow(() -> SpiceAgent.premain(null, null));
    }

    @Test
    void premain_emptyArgs_doesNotThrow() {
        assertDoesNotThrow(() -> SpiceAgent.premain("", null));
    }

    @Test
    void premain_nonexistentFile_doesNotThrow() {
        // Should fail to load config but not throw (agent must never break the target)
        assertDoesNotThrow(() -> SpiceAgent.premain("/nonexistent/path/probes.json", null));
    }
}
