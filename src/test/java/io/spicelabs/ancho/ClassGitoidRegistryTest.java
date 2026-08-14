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

import org.junit.jupiter.api.Test;

class ClassGitoidRegistryTest {

    @Test
    void registerThenLookup_roundTrips() {
        ClassLoader loader = new ClassLoader() {};
        ClassGitoidRegistry.register(loader, "com.example.Foo", "gitoid:blob:sha256:abc");
        assertEquals("gitoid:blob:sha256:abc",
                ClassGitoidRegistry.lookup(loader, "com.example.Foo"));
    }

    @Test
    void lookup_unregistered_returnsNull() {
        ClassLoader loader = new ClassLoader() {};
        assertNull(ClassGitoidRegistry.lookup(loader, "com.example.NotRegistered"));
    }

    @Test
    void sameNameDifferentLoaders_areDistinct() {
        ClassLoader a = new ClassLoader() {};
        ClassLoader b = new ClassLoader() {};
        ClassGitoidRegistry.register(a, "com.example.Dup", "gitoid:blob:sha256:aaa");
        ClassGitoidRegistry.register(b, "com.example.Dup", "gitoid:blob:sha256:bbb");
        assertEquals("gitoid:blob:sha256:aaa", ClassGitoidRegistry.lookup(a, "com.example.Dup"));
        assertEquals("gitoid:blob:sha256:bbb", ClassGitoidRegistry.lookup(b, "com.example.Dup"));
    }

    @Test
    void register_nullArgs_areNoOps() {
        ClassLoader loader = new ClassLoader() {};
        assertDoesNotThrow(() -> ClassGitoidRegistry.register(loader, null, "g"));
        assertDoesNotThrow(() -> ClassGitoidRegistry.register(loader, "com.example.X", null));
        assertNull(ClassGitoidRegistry.lookup(loader, null));
        assertNull(ClassGitoidRegistry.lookup(loader, "com.example.X"));
    }
}
