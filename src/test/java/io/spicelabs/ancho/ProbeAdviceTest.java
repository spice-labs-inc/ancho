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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbeAdviceTest {

    @BeforeEach
    void setUp() {
        ProbeAdvice.EVENT_CLASS_MAP.clear();
    }

    @Test
    void onEnter_unknownMethodKey_doesNotThrow() {
        assertDoesNotThrow(() -> ProbeAdvice.onEnter("unknown.Class#unknownMethod#()V"));
    }

    @Test
    void eventClassMap_isPopulatable() {
        ProbeAdvice.EVENT_CLASS_MAP.put(
                "com.example.Foo#bar#()V",
                "io.spicelabs.ancho.events.spice_probe_test");

        assertEquals("io.spicelabs.ancho.events.spice_probe_test",
                ProbeAdvice.EVENT_CLASS_MAP.get("com.example.Foo#bar#()V"));
    }

    @Test
    void onEnter_withMapping_doesNotThrow() {
        // Even if the event class doesn't exist, the advice should catch and swallow
        ProbeAdvice.EVENT_CLASS_MAP.put("test#test#()V", "nonexistent.EventClass");
        assertDoesNotThrow(() -> ProbeAdvice.onEnter("test#test#()V"));
    }
}
