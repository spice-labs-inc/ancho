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

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
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
 * <p>The advice class ({@code ProbeAdvice}) lives on the bootstrap classloader
 * (injected by {@link BootstrapInjector}) so it's visible from instrumented
 * JDK classes like {@code javax.crypto.Cipher}. We resolve it via
 * {@link ClassFileLocator} to avoid loading it on the app classloader,
 * which would create a duplicate class and confuse ByteBuddy.
 */
public class ProbeInstaller {

    private static final String ADVICE_CLASS_NAME = "io.spicelabs.ancho.ProbeAdvice";

    /**
     * Install instrumentation for all probes.
     */
    public static void install(ProbeConfig config, Map<String, String> eventClassNames,
                               Instrumentation inst) {
        // Populate the advice's EVENT_CLASS_MAP via the bootstrap-loaded class.
        // We must use reflection because the app CL must NOT load ProbeAdvice
        // (it's on the bootstrap CL only).
        try {
            Class<?> adviceClass = Class.forName(ADVICE_CLASS_NAME, true, null);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> eventMap = (java.util.Map<String, String>)
                    adviceClass.getField("EVENT_CLASS_MAP").get(null);

            for (ProbeConfig.Probe probe : config.getProbes()) {
                String eventClassName = eventClassNames.get(probe.id);
                if (eventClassName == null) continue;
                // Key with descriptor for exact match
                if (probe.descriptor != null) {
                    eventMap.put(probe.className + "|" + probe.method + "|" + probe.descriptor, eventClassName);
                }
                // Key without descriptor as fallback
                eventMap.put(probe.className + "|" + probe.method, eventClassName);
            }
        } catch (Exception e) {
            SpiceAgent.log("WARN: Failed to populate event map: " + e.getMessage());
            return; // Can't instrument without the map
        }

        SpiceAgent.log("Installing ByteBuddy instrumentation for " +
                config.getByClass().size() + " classes, " +
                config.getProbes().size() + " probes");

        // Resolve the advice class bytes from the agent JAR itself (our own classloader).
        // We use our CL's locator so ByteBuddy reads the class file with the correct
        // (potentially shaded) annotation references. The class is also on the bootstrap
        // CL (via BootstrapInjector) so it's visible at runtime from JDK classes.
        ClassFileLocator adviceLocator = ClassFileLocator.ForClassLoader.of(
                ProbeInstaller.class.getClassLoader());

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
                .ignore(none()); // Don't skip JDK classes

        for (Map.Entry<String, List<ProbeConfig.Probe>> entry : config.getByClass().entrySet()) {
            String classFqn = entry.getKey();
            List<ProbeConfig.Probe> probes = entry.getValue();

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
                            try {
                                return b.visit(
                                    Advice.to(
                                        net.bytebuddy.pool.TypePool.Default.of(adviceLocator)
                                            .describe(ADVICE_CLASS_NAME)
                                            .resolve(),
                                        adviceLocator
                                    ).on(finalMethodMatcher)
                                );
                            } catch (Exception e) {
                                SpiceAgent.log("WARN: Advice resolution failed for " +
                                    typeDescription.getName() + ": " + e.getMessage());
                                return b;
                            }
                        }
                    });
        }

        builder.installOn(inst);
        SpiceAgent.log("ByteBuddy instrumentation installed");

        // Retransform already-loaded classes that match our probes.
        if (inst.isRetransformClassesSupported()) {
            for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
                String name = loadedClass.getName();
                if (config.hasClass(name) && inst.isModifiableClass(loadedClass)) {
                    try {
                        inst.retransformClasses(loadedClass);
                        SpiceAgent.log("Retransformed: " + name);
                    } catch (Throwable t) {
                        SpiceAgent.log("WARN: Failed to retransform " + name + ": " + t.getMessage());
                    }
                }
            }
        }
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
