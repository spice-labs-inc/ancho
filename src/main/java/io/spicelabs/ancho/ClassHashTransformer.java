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

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the SHA-256 / gitoid of every {@code .class} actually loaded (and the JAR
 * it came from) and emits one {@code spice.ClassLoaded} JFR event per distinct class.
 *
 * <p>The hashes are computed to be <em>byte-identical</em> to what goatrodeo records
 * during an inventory survey, so runtime usage can later be correlated with the
 * inventory ADG:
 * <ul>
 *   <li>{@code classSha256} = plain {@code sha256(classfileBuffer)} — matches goatrodeo's
 *       {@code sha256:} alias.</li>
 *   <li>{@code classGitoid} = {@code sha256("blob " + len + "\0" + content)} — matches
 *       goatrodeo's primary {@code gitoid:blob:sha256:} node id.</li>
 * </ul>
 *
 * <p>The {@code ClassFileTransformer} receives the decompressed, pre-instrumentation
 * class bytes, which is exactly what goatrodeo hashes. We always {@code return null}
 * (never modify bytes). Registered <em>before</em> ByteBuddy's transformer so we observe
 * original bytes; see the agent wire-up in {@link SpiceAgent#premain}.
 *
 * <p>Scope: only classes whose {@code ProtectionDomain} has a non-null {@code CodeSource}
 * location (jars + directories — app and libraries). JDK/bootstrap and dynamically
 * generated classes (lambdas, proxies) have no {@code CodeSource} and aren't in inventory,
 * so they're skipped.
 */
public class ClassHashTransformer implements ClassFileTransformer {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Re-entrancy guard. Hashing and committing a JFR event can trigger class loading,
     * which would re-enter {@code transform()} (potentially while holding a classloader
     * lock during early VM init). When set, we return immediately without doing any work.
     */
    private static final ThreadLocal<Boolean> IN_HASH = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /** Coarse dedup so we emit one event per (loader-identity, class name). */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * Per-location cache of {gitoid, sha256} for JAR files. {@link #NO_JAR_HASHES} is a
     * sentinel for "computed, but no jar hash available" (dirs, unreadable, non-file URLs).
     */
    private final ConcurrentHashMap<String, String[]> jarHashCache = new ConcurrentHashMap<>();
    private static final String[] NO_JAR_HASHES = new String[0];

    // Reflective handles to the bootstrap-injected spice.ClassLoaded event class. Resolved once.
    private volatile Class<?> eventClass;
    private volatile Constructor<?> eventCtor;
    private volatile Field[] eventFields; // aligned with EventClassGenerator.CLASS_LOADED_FIELDS
    private volatile Method commitMethod;

    // Reflective handle to ClassGitoidRegistry.register on the bootstrap copy. Resolved once.
    private volatile Method registerMethod;
    private volatile boolean registryChecked;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // Never modify bytes. Bail before any work if we're re-entered or lack what we need.
        if (Boolean.TRUE.equals(IN_HASH.get())) {
            return null;
        }
        if (className == null || classfileBuffer == null || isSkipped(className)) {
            return null;
        }
        if (protectionDomain == null) {
            return null;
        }
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null; // JDK/bootstrap + dynamically generated classes — not in inventory
        }

        IN_HASH.set(Boolean.TRUE);
        try {
            String dedupKey = System.identityHashCode(loader) + "#" + className;
            if (!seen.add(dedupKey)) {
                return null;
            }

            URL location = codeSource.getLocation();
            String[] jarHashes = jarHashes(location);

            String[] values = new String[] {
                    className.replace('/', '.'),       // className
                    gitoid(classfileBuffer),           // classGitoid
                    sha256Hex(classfileBuffer),        // classSha256
                    location.toString(),               // codeSource
                    jarHashes != null ? jarHashes[0] : null, // jarGitoid
                    jarHashes != null ? jarHashes[1] : null  // jarSha256
            };
            // Record (loader, name) -> classGitoid so ProbeAdvice can stamp events later.
            registerGitoid(loader, values[0], values[1]);
            emit(values);
        } catch (Throwable t) {
            // Never break the target application.
        } finally {
            IN_HASH.set(Boolean.FALSE);
        }
        return null;
    }

    /**
     * Skip the agent's own classes (incl. shaded ByteBuddy/ASM and generated events) and
     * JFR internals, which have a real {@code CodeSource} during tests/builds and could
     * otherwise feed back into the transformer.
     */
    static boolean isSkipped(String internalName) {
        return internalName.startsWith("io/spicelabs/ancho/")
                || internalName.startsWith("jdk/jfr/")
                || internalName.startsWith("net/bytebuddy/");
    }

    /** {gitoid, sha256} of the jar at {@code location}, or null. Best-effort; cached per location. */
    private String[] jarHashes(URL location) {
        String key = location.toString();
        String[] cached = jarHashCache.get(key);
        if (cached != null) {
            return cached == NO_JAR_HASHES ? null : cached;
        }
        String[] computed = computeJarHashes(location);
        jarHashCache.put(key, computed == null ? NO_JAR_HASHES : computed);
        return computed;
    }

    private static String[] computeJarHashes(URL location) {
        try {
            if (!"file".equals(location.getProtocol())) {
                return null; // nested fat-jar entries etc. — class hash still correlates
            }
            Path path = Paths.get(location.toURI());
            if (!Files.isRegularFile(path)) {
                return null; // exploded classpath dir — no jar to hash
            }
            byte[] bytes = Files.readAllBytes(path);
            return new String[] { gitoid(bytes), sha256Hex(bytes) };
        } catch (Throwable t) {
            return null;
        }
    }

    private void emit(String[] values) throws Exception {
        if (!resolveEvent()) {
            return;
        }
        Object event = eventCtor.newInstance();
        for (int i = 0; i < eventFields.length; i++) {
            if (values[i] != null) {
                eventFields[i].set(event, values[i]);
            }
        }
        commitMethod.invoke(event);
    }

    /** Resolve and cache the bootstrap-injected event class + its field/ctor/commit handles. */
    private boolean resolveEvent() {
        if (eventClass != null) {
            return true;
        }
        synchronized (this) {
            if (eventClass != null) {
                return true;
            }
            try {
                Class<?> ec = lookupEventClass();
                String[] names = EventClassGenerator.CLASS_LOADED_FIELDS;
                Field[] fields = new Field[names.length];
                for (int i = 0; i < names.length; i++) {
                    fields[i] = ec.getField(names[i]);
                }
                this.eventCtor = ec.getConstructor();
                this.commitMethod = ec.getMethod("commit");
                this.eventFields = fields;
                this.eventClass = ec; // publish last — gates the other handles
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * Resolve the {@code spice.ClassLoaded} event class, which production code loads from the
     * bootstrap classloader (where {@link EventClassGenerator} injected it). Package-private and
     * overridable so tests can supply the class without bootstrap injection.
     */
    Class<?> lookupEventClass() throws ClassNotFoundException {
        return Class.forName(EventClassGenerator.CLASS_LOADED_FQN, true, null);
    }

    /** Record this class's gitoid in the bootstrap registry so ProbeAdvice can stamp events. */
    private void registerGitoid(ClassLoader loader, String binaryName, String gitoid) {
        Method register = resolveRegisterMethod();
        if (register == null) {
            return;
        }
        try {
            register.invoke(null, loader, binaryName, gitoid);
        } catch (Throwable t) {
            // best-effort
        }
    }

    /**
     * Resolve {@code ClassGitoidRegistry.register} on the <em>bootstrap</em> copy (forced via
     * {@code Class.forName(name, true, null)}) so we write the same map the inlined advice reads.
     * Returns null if the registry isn't on the bootstrap classloader (e.g. unit tests).
     */
    private Method resolveRegisterMethod() {
        if (registerMethod != null) {
            return registerMethod;
        }
        if (registryChecked) {
            return null;
        }
        synchronized (this) {
            if (registerMethod == null && !registryChecked) {
                registryChecked = true;
                try {
                    Class<?> registry = Class.forName("io.spicelabs.ancho.ClassGitoidRegistry", true, null);
                    registerMethod = registry.getMethod("register",
                            ClassLoader.class, String.class, String.class);
                } catch (Throwable t) {
                    // Registry not on bootstrap — declaring-class stamping is simply unavailable.
                }
            }
        }
        return registerMethod;
    }

    /** goatrodeo primary id: {@code gitoid:blob:sha256:<hex(sha256("blob " + len + "\0" + content))>}. */
    static String gitoid(byte[] content) throws Exception {
        // Prefix and content stream into ONE digest; do not sha256(content) then prepend.
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(("blob " + content.length + " ").getBytes(ASCII));
        md.update(content);
        return "gitoid:blob:sha256:" + hex(md.digest());
    }

    /** goatrodeo {@code sha256:} alias: plain unframed SHA-256 of the exact bytes. */
    static String sha256Hex(byte[] content) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
