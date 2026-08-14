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

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClassHashTransformer}.
 *
 * <p>The gitoid/sha256 vectors below are independently verified:
 * {@code printf 'abc' | sha256sum} and {@code printf 'blob 3\0abc' | sha256sum}.
 * {@code sha256("abc")} is also the canonical NIST SHA-256 test vector.
 */
class ClassHashTransformerTest {

    private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);

    @Test
    void sha256Hex_matchesKnownVector() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ClassHashTransformer.sha256Hex(ABC));
    }

    @Test
    void gitoid_matchesKnownVector() throws Exception {
        // Must match goatrodeo's primary gitoid:blob:sha256 node id, i.e. the "blob <len>\0"
        // framing — NOT a plain sha256 of the content.
        assertEquals("gitoid:blob:sha256:c1cf6e465077930e88dc5136641d402f72a229ddd996f627d60e9639eaba35a6",
                ClassHashTransformer.gitoid(ABC));
    }

    @Test
    void gitoid_differsFromPlainSha256() throws Exception {
        // Guards against the naive "sha256(content)" mistake: the gitoid hex must not be the
        // plain sha256 hex.
        assertNotEquals(ClassHashTransformer.sha256Hex(ABC),
                ClassHashTransformer.gitoid(ABC).substring("gitoid:blob:sha256:".length()));
    }

    @Test
    void transform_alwaysReturnsNull_andNeverThrows() {
        ClassHashTransformer t = new ClassHashTransformer();
        // null protection domain
        assertNull(t.transform(null, "com/example/Foo", null, null, new byte[] {1, 2, 3, 4}));
        // protection domain with no code source
        ProtectionDomain noCs = new ProtectionDomain(null, null);
        assertNull(t.transform(null, "com/example/Bar", null, noCs, new byte[] {1, 2, 3, 4}));
        // code source with null location
        ProtectionDomain nullLoc =
                new ProtectionDomain(new CodeSource(null, (java.security.cert.Certificate[]) null), null);
        assertNull(t.transform(null, "com/example/Baz", null, nullLoc, new byte[] {1, 2, 3, 4}));
        // null class name / null buffer
        assertNull(t.transform(null, null, null, noCs, new byte[] {1, 2, 3, 4}));
        assertNull(t.transform(null, "com/example/Qux", null, noCs, null));
    }

    @Test
    void transform_withRealCodeSource_returnsNull_andTolerantWhenEventClassAbsent() throws Exception {
        // The spice.ClassLoaded event class is not on the bootstrap CL in a plain unit test,
        // so emit() resolves nothing — transform must still complete and return null.
        ClassHashTransformer t = new ClassHashTransformer();
        ProtectionDomain pd = new ProtectionDomain(
                new CodeSource(new URL("file:/tmp/does-not-exist.jar"), (java.security.cert.Certificate[]) null),
                null);
        assertNull(t.transform(null, "com/example/Loaded", null, pd, ABC));
        // Second call for the same (loader, name) is deduped — still null, still no throw.
        assertNull(t.transform(null, "com/example/Loaded", null, pd, ABC));
    }

    @Test
    void transform_emitsOneClassLoadedEventWithCorrectHashes() throws Exception {
        // Define the event class in a child loader (no bootstrap injection) and feed it in.
        final Class<?> eventClass = new BytesClassLoader()
                .define(EventClassGenerator.CLASS_LOADED_FQN, EventClassGenerator.generateClassLoadedEvent());
        ClassHashTransformer t = new ClassHashTransformer() {
            @Override
            Class<?> lookupEventClass() {
                return eventClass;
            }
        };

        // A real file at the code-source location exercises the jar-hash path.
        Path jar = Files.createTempFile("fake", ".jar");
        byte[] jarBytes = "fake jar contents".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, jarBytes);
        ProtectionDomain pd = new ProtectionDomain(
                new CodeSource(jar.toUri().toURL(), (Certificate[]) null), null);
        byte[] classBytes = "class-file-bytes".getBytes(StandardCharsets.UTF_8);

        Path rec = Files.createTempFile("rec", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("spice.ClassLoaded");
            r.start();
            assertNull(t.transform(null, "org/example/Foo", null, pd, classBytes));
            assertNull(t.transform(null, "org/example/Foo", null, pd, classBytes)); // dedup
            r.stop();
            r.dump(rec);
        }

        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(rec)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if ("spice.ClassLoaded".equals(e.getEventType().getName())) {
                    events.add(e);
                }
            }
        }

        assertEquals(1, events.size(), "dedup: one event per (loader, class)");
        RecordedEvent e = events.get(0);
        assertEquals("org.example.Foo", e.getString("className"));
        assertEquals(ClassHashTransformer.sha256Hex(classBytes), e.getString("classSha256"));
        assertEquals(ClassHashTransformer.gitoid(classBytes), e.getString("classGitoid"));
        assertEquals(ClassHashTransformer.sha256Hex(jarBytes), e.getString("jarSha256"));
        assertEquals(ClassHashTransformer.gitoid(jarBytes), e.getString("jarGitoid"));
        assertEquals(jar.toUri().toURL().toString(), e.getString("codeSource"));

        Files.deleteIfExists(jar);
        Files.deleteIfExists(rec);
    }

    @Test
    void transform_reentrantCallDoesNoWork() throws Exception {
        // Simulate the hazard: committing/hashing triggers another class load → transform() again.
        // The re-entrant call must short-circuit (return null) before resolving the event class,
        // so only the outer call emits.
        final Class<?> eventClass = new BytesClassLoader()
                .define(EventClassGenerator.CLASS_LOADED_FQN, EventClassGenerator.generateClassLoadedEvent());
        final int[] lookups = {0};
        final ClassHashTransformer[] holder = new ClassHashTransformer[1];
        ClassHashTransformer t = new ClassHashTransformer() {
            @Override
            Class<?> lookupEventClass() {
                lookups[0]++;
                // Re-enter from within the outer transform's emit() resolution.
                ProtectionDomain pd2 = mkPd("file:/tmp/reentrant.jar");
                assertNull(holder[0].transform(null, "org/example/Reentrant", null, pd2, ABC),
                        "re-entrant transform must return null");
                return eventClass;
            }
        };
        holder[0] = t;

        try (Recording r = new Recording()) {
            r.enable("spice.ClassLoaded");
            r.start();
            assertNull(t.transform(null, "org/example/Outer", null, mkPd("file:/tmp/outer.jar"), ABC));
            r.stop();
        }
        // The re-entrant call bailed before lookupEventClass(), so it ran exactly once.
        assertEquals(1, lookups[0], "re-entrant call must not reach event resolution");
    }

    @Test
    void transform_registersClassGitoidForProbeStamping() throws Exception {
        // With the registry seam supplied (in production: the bootstrap-injected copy), each
        // hashed class's gitoid must land in the registry so ProbeAdvice can stamp probe events.
        ClassHashTransformer t = new ClassHashTransformer() {
            @Override
            Class<?> lookupRegistryClass() {
                return ClassGitoidRegistry.class;
            }
        };
        ClassLoader loader = new ClassLoader() {};
        assertNull(t.transform(loader, "org/example/Stamped", null, mkPd("file:/tmp/stamped.jar"), ABC));
        assertEquals(ClassHashTransformer.gitoid(ABC),
                ClassGitoidRegistry.lookup(loader, "org.example.Stamped"),
                "transform must register the class gitoid under (loader, binary name)");
    }

    @Test
    void transform_withoutRegistry_stillCompletes() {
        // No bootstrap injection and no seam: registry resolution fails once, quietly, and
        // hashing continues.
        ClassHashTransformer t = new ClassHashTransformer() {
            @Override
            Class<?> lookupRegistryClass() throws ClassNotFoundException {
                throw new ClassNotFoundException("no bootstrap registry");
            }
        };
        ClassLoader loader = new ClassLoader() {};
        assertNull(t.transform(loader, "org/example/NoRegistry", null, mkPd("file:/tmp/nr.jar"), ABC));
        assertNull(ClassGitoidRegistry.lookup(loader, "org.example.NoRegistry"));
    }

    private static ProtectionDomain mkPd(String url) {
        try {
            return new ProtectionDomain(new CodeSource(new URL(url), (Certificate[]) null), null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Minimal loader so we can define the generated event class off-bootstrap for tests. */
    private static final class BytesClassLoader extends ClassLoader {
        BytesClassLoader() {
            super(ClassHashTransformerTest.class.getClassLoader());
        }

        Class<?> define(String fqn, byte[] bytes) {
            return defineClass(fqn, bytes, 0, bytes.length);
        }
    }

    @Test
    void isSkipped_coversAgentAndJfrAndByteBuddy() {
        assertTrue(ClassHashTransformer.isSkipped("io/spicelabs/ancho/ClassHashTransformer"));
        assertTrue(ClassHashTransformer.isSkipped("io/spicelabs/ancho/events/SpiceClassLoaded"));
        assertTrue(ClassHashTransformer.isSkipped("io/spicelabs/ancho/shaded/bytebuddy/jar/asm/ClassWriter"));
        assertTrue(ClassHashTransformer.isSkipped("jdk/jfr/Event"));
        assertTrue(ClassHashTransformer.isSkipped("net/bytebuddy/agent/Foo"));
        assertFalse(ClassHashTransformer.isSkipped("org/apache/logging/log4j/core/Logger"));
        assertFalse(ClassHashTransformer.isSkipped("com/example/App"));
    }
}
