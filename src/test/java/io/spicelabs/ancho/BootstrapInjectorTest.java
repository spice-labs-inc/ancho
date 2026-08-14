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

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BootstrapInjectorTest {

    /**
     * Every class injected onto the bootstrap loader must resolve to a real class file — a missing
     * or misnamed entry (e.g. forgetting a nested class like {@code ClassGitoidRegistry$LoaderKey})
     * makes the agent throw {@code NoClassDefFoundError} at runtime and silently drop probe events.
     */
    @Test
    void everyBootstrapResourceResolves() {
        for (String resource : BootstrapInjector.BOOTSTRAP_CLASS_RESOURCES) {
            try (InputStream is = BootstrapInjector.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(is, "bootstrap-injected resource not found on classpath: " + resource);
            } catch (Exception e) {
                fail("error reading bootstrap resource " + resource + ": " + e);
            }
        }
    }

    /**
     * Guard against adding a nested class to a bootstrap helper without listing it for injection:
     * for each injected top-level helper, every {@code Helper$Nested.class} it declares must also
     * be in the injection list.
     */
    @Test
    void allNestedClassesOfInjectedHelpersAreListed() throws Exception {
        Set<String> listed = new HashSet<>();
        for (String r : BootstrapInjector.BOOTSTRAP_CLASS_RESOURCES) {
            listed.add(r);
        }
        for (String r : BootstrapInjector.BOOTSTRAP_CLASS_RESOURCES) {
            if (r.indexOf('$') >= 0) {
                continue; // already a nested entry
            }
            String fqn = r.substring(0, r.length() - ".class".length()).replace('/', '.');
            Class<?> helper = Class.forName(fqn);
            for (Class<?> nested : helper.getDeclaredClasses()) {
                String resource = nested.getName().replace('.', '/') + ".class";
                assertTrue(listed.contains(resource),
                        "nested class " + nested.getName() + " of bootstrap helper " + fqn
                                + " is not in BOOTSTRAP_CLASS_RESOURCES");
            }
        }
    }
}
