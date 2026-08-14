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

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.TestCaller;

class ProbeAdviceTest {

    @BeforeEach
    void setUp() {
        ProbeAdvice.EVENT_CLASS_MAP.clear();
        ProbeAdvice.HANDLES_CACHE.clear();
        FakeEvent.lastCommitted = null;
    }

    @Test
    void onEnter_unknownMethodKey_doesNotThrow() {
        assertDoesNotThrow(() -> ProbeAdvice.onEnter("unknown.Class#unknownMethod#()V", null));
    }

    @Test
    void onEnter_withDeclaringClass_doesNotThrow() {
        // declaringClass present but unmapped methodKey — must still be null-safe.
        assertDoesNotThrow(() -> ProbeAdvice.onEnter("unknown#unknown#()V", String.class));
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
        assertDoesNotThrow(() -> ProbeAdvice.onEnter("test#test#()V", String.class));
    }

    @Test
    void record_stampsDeclaringClassGitoid() throws Exception {
        mapFakeEvent("com.example.App|encrypt|()V");
        ClassGitoidRegistry.register(ProbeAdviceTest.class.getClassLoader(),
                ProbeAdviceTest.class.getName(), "gitoid:blob:sha256:declaring");

        ProbeAdvice.onEnter("com.example.App|encrypt|()V", ProbeAdviceTest.class);

        FakeEvent event = FakeEvent.lastCommitted;
        assertNotNull(event, "the probe event must be committed");
        assertEquals("gitoid:blob:sha256:declaring", event.classGitoid,
                "the event must carry the declaring class's registered gitoid");
        assertNull(event.callerGitoids,
                "no callers are registered, so callerGitoids must stay absent");
    }

    @Test
    void record_stampsCallerGitoids() throws Exception {
        mapFakeEvent("com.example.App|decrypt|()V");
        ClassGitoidRegistry.register(TestCaller.class.getClassLoader(),
                TestCaller.class.getName(), "gitoid:blob:sha256:probecaller");

        // Route through TestCaller (com.example) so a registered caller frame is on the stack.
        TestCaller.callRecord("com.example.App|decrypt|()V", ProbeAdviceTest.class);

        FakeEvent event = FakeEvent.lastCommitted;
        assertNotNull(event, "the probe event must be committed");
        assertNotNull(event.callerGitoids, "the registered caller must be stamped");
        assertTrue(Arrays.asList(event.callerGitoids.split("\n"))
                        .contains("gitoid:blob:sha256:probecaller"),
                "callerGitoids must include the registered TestCaller gitoid");
    }

    @Test
    void record_unregisteredDeclaringClass_leavesStampsAbsent() throws Exception {
        mapFakeEvent("com.example.App|sign|()V");

        // Unregistered has no registry entry — stamps must stay absent, never wrong.
        ProbeAdvice.onEnter("com.example.App|sign|()V", Unregistered.class);

        FakeEvent event = FakeEvent.lastCommitted;
        assertNotNull(event, "the event must still be committed without registry entries");
        assertNull(event.classGitoid, "no registry entry → classGitoid absent");
    }

    @Test
    void record_eventClassWithoutStampFields_stillCommits() throws Exception {
        ProbeAdvice.EVENT_CLASS_MAP.put("com.example.App|verify|()V", "fake.FieldlessEvent");
        ProbeAdvice.HANDLES_CACHE.put("fake.FieldlessEvent",
                new ProbeAdvice.EventHandles(FieldlessEvent.class));
        ClassGitoidRegistry.register(ProbeAdviceTest.class.getClassLoader(),
                ProbeAdviceTest.class.getName(), "gitoid:blob:sha256:declaring");
        FieldlessEvent.commits = 0;

        assertDoesNotThrow(() ->
                ProbeAdvice.onEnter("com.example.App|verify|()V", ProbeAdviceTest.class));
        assertEquals(1, FieldlessEvent.commits,
                "an event class without stamp fields must still commit");
    }

    /**
     * Map a method key to {@link FakeEvent} via a pre-populated handles cache, bypassing the
     * bootstrap-only {@code Class.forName} path that unit tests can't exercise.
     */
    private static void mapFakeEvent(String methodKey) throws Exception {
        ProbeAdvice.EVENT_CLASS_MAP.put(methodKey, "fake.Event");
        ProbeAdvice.HANDLES_CACHE.put("fake.Event", new ProbeAdvice.EventHandles(FakeEvent.class));
    }

    /**
     * Stand-in for a generated probe event: same reflective surface (public no-arg ctor,
     * {@code commit()}, public stamp fields) but captures the committed instance for assertions.
     */
    public static class FakeEvent {
        static volatile FakeEvent lastCommitted;

        public String classGitoid;
        public String callerGitoids;

        public void commit() {
            lastCommitted = this;
        }
    }

    /** Event shape without the stamp fields — e.g. a foreign or older event class. */
    public static class FieldlessEvent {
        static volatile int commits;

        public void commit() {
            commits++;
        }
    }

    /** Never registered in the {@link ClassGitoidRegistry}. */
    private static final class Unregistered {
    }
}
