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

import net.bytebuddy.asm.Advice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ByteBuddy advice injected at method entry for instrumented probe methods.
 *
 * <p>When a probed method is called, this advice instantiates the corresponding
 * dynamically-generated JFR event class and commits it. JFR captures the stack
 * trace, timestamp, and thread automatically.
 *
 * <p>The event class name is resolved from {@link #EVENT_CLASS_MAP} which is
 * populated at agent startup by {@link ProbeInstaller}.
 */
public class ProbeAdvice {

    /**
     * Map from instrumented method key ("classFqn#method#descriptor") to the
     * FQN of the generated JFR event class. Populated at agent startup.
     */
    public static final Map<String, String> EVENT_CLASS_MAP = new ConcurrentHashMap<>();

    /**
     * Cache of resolved event classes to avoid repeated Class.forName() calls.
     */
    public static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    /**
     * Injected at method entry. Creates and commits a JFR event.
     *
     * <p>The {@code methodKey} is set by ByteBuddy via {@code @Advice.Origin}.
     * We use it to look up the generated event class.
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin("#t|#m|#d") String methodKey) {
        try {
            String eventClassName = EVENT_CLASS_MAP.get(methodKey);
            if (eventClassName == null) {
                // Fallback: try without descriptor (class|method only)
                int lastPipe = methodKey.lastIndexOf('|');
                if (lastPipe > 0) {
                    eventClassName = EVENT_CLASS_MAP.get(methodKey.substring(0, lastPipe));
                }
            }
            if (eventClassName == null) return;

            Class<?> eventClass = CLASS_CACHE.get(eventClassName);
            if (eventClass == null) {
                // Event classes are on the bootstrap classloader
                eventClass = Class.forName(eventClassName, true, null);
                CLASS_CACHE.put(eventClassName, eventClass);
            }

            // jdk.jfr.Event has begin() and commit() methods
            Object event = eventClass.getConstructor().newInstance();
            eventClass.getMethod("commit").invoke(event);
        } catch (Throwable t) {
            // Never let instrumentation break the target application
        }
    }
}
