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

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * Spice Labs JFR instrumentation agent entry point.
 *
 * <p>Loaded via {@code -javaagent:ancho.jar=<path-to-probes.json>}
 *
 * <p>Startup flow:
 * <ol>
 *   <li>Parse the probe config JSON</li>
 *   <li>Check if {@code jdk.jfr.Event} is available (skip event generation on Oracle JDK 8)</li>
 *   <li>Generate JFR event classes using ASM and load via bootstrap classloader</li>
 *   <li>Install ByteBuddy instrumentation for all probe methods</li>
 * </ol>
 *
 * <p>If anything fails, the agent logs a warning and returns without throwing —
 * the target application must never be broken by instrumentation.
 */
public class SpiceAgent {

    private static final String LOG_PREFIX = "[Spice Agent] ";

    /**
     * Java agent entry point.
     *
     * @param agentArgs path to the probe config JSON file
     * @param inst      instrumentation instance provided by the JVM
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            log("Starting Spice Labs JFR agent");

            // 1. Parse probe config
            if (agentArgs == null || agentArgs.trim().isEmpty()) {
                log("WARN: No probe config path provided. Agent will not instrument anything.");
                return;
            }

            String configPath = agentArgs.trim();
            log("Loading probe config from: " + configPath);

            ProbeConfig config = ProbeConfig.load(configPath);
            log("Loaded " + config.getProbes().size() + " probes for " +
                    config.getByClass().size() + " classes");

            if (config.getProbes().isEmpty()) {
                log("No probes configured. Agent will not instrument anything.");
                return;
            }

            // 2. Check if JFR is available
            if (!isJfrAvailable()) {
                log("WARN: jdk.jfr.Event not available on this JVM. " +
                        "Custom probe events will not be emitted. " +
                        "Native JDK security events may still be captured via JFC settings.");
                return;
            }

            // 3. Generate JFR event classes + bootstrap ProbeAdvice
            Map<String, String> eventClassNames = EventClassGenerator.generateAndLoad(
                    config.getProbes(), inst);
            log("Generated " + eventClassNames.size() + " JFR event classes");

            // 4. Inject ProbeAdvice onto bootstrap classloader so it's visible
            //    from instrumented JDK classes (Cipher, MessageDigest, etc.)
            BootstrapInjector.inject(inst);

            // 5. Install ByteBuddy instrumentation
            ProbeInstaller.install(config, eventClassNames, inst);

            log("Agent startup complete. " + config.getProbes().size() +
                    " methods will emit JFR events.");

        } catch (Throwable t) {
            log("ERROR: Agent startup failed: " + t.getMessage());
            t.printStackTrace(System.err);
            // Don't throw — never break the target application
        }
    }

    /**
     * Check if the JFR Event API is available on this JVM.
     * Returns false on Oracle JDK 8 (pre-open-source JFR).
     */
    static boolean isJfrAvailable() {
        try {
            Class.forName("jdk.jfr.Event");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Simple logging — agents can't rely on SLF4J or other frameworks
     * being on the classpath.
     */
    public static void log(String message) {
        System.out.println(LOG_PREFIX + message);
    }
}
