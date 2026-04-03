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

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Installs ByteBuddy instrumentation for all probe definitions.
 *
 * <p>For each probe, registers a type matcher + method matcher that injects
 * {@link ProbeAdvice} at method entry. The advice emits a JFR event for
 * every call to the instrumented method.
 */
public class ProbeInstaller {

    /**
     * Install instrumentation for all probes.
     *
     * @param config          parsed probe configuration
     * @param eventClassNames map from probe ID → generated event class FQN
     * @param inst            the Instrumentation instance
     */
    public static void install(ProbeConfig config, Map<String, String> eventClassNames,
                               Instrumentation inst) {
        // Populate the advice's lookup map: "classFqn#method#descriptor" → event class name
        for (ProbeConfig.Probe probe : config.getProbes()) {
            String eventClassName = eventClassNames.get(probe.id);
            if (eventClassName == null) continue;

            String key = probe.className + "#" + probe.method + "#" + probe.descriptor;
            ProbeAdvice.EVENT_CLASS_MAP.put(key, eventClassName);
        }

        SpiceAgent.log("Installing ByteBuddy instrumentation for " +
                config.getByClass().size() + " classes, " +
                config.getProbes().size() + " probes");

        // Build a single AgentBuilder with all type+method matchers
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        JavaModule module, boolean loaded, Throwable throwable) {
                        SpiceAgent.log("WARN: Failed to instrument " + typeName +
                                ": " + throwable.getMessage());
                    }
                })
                .ignore(none()); // Don't skip JDK classes — we instrument them

        for (Map.Entry<String, List<ProbeConfig.Probe>> entry : config.getByClass().entrySet()) {
            String classFqn = entry.getKey();
            List<ProbeConfig.Probe> probes = entry.getValue();

            // Build method matcher: match any of the probed methods in this class
            ElementMatcher.Junction<MethodDescription> methodMatcher = none();
            for (ProbeConfig.Probe probe : probes) {
                ElementMatcher.Junction<MethodDescription> single = named(probe.method);
                if (probe.descriptor != null) {
                    single = single.and(hasDescriptor(probe.descriptor));
                }
                methodMatcher = methodMatcher.or(single);
            }

            final ElementMatcher.Junction<MethodDescription> finalMethodMatcher = methodMatcher;

            builder = builder
                    .type(named(classFqn))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                                 TypeDescription typeDescription,
                                                                 ClassLoader classLoader,
                                                                 JavaModule module,
                                                                 ProtectionDomain protectionDomain) {
                            return b.visit(Advice.to(ProbeAdvice.class).on(finalMethodMatcher));
                        }
                    });
        }

        builder.installOn(inst);
        SpiceAgent.log("ByteBuddy instrumentation installed");
    }

    /**
     * Build a method matcher for a single descriptor (package-visible for testing).
     */
    static ElementMatcher.Junction<MethodDescription> hasDescriptor(String descriptor) {
        return new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
            @Override
            public boolean matches(MethodDescription target) {
                return descriptor.equals(target.getDescriptor());
            }
        };
    }
}
