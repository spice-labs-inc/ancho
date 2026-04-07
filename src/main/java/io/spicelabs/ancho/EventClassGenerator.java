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

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Generates JFR event classes dynamically using ASM (from ByteBuddy's shaded copy).
 *
 * <p>For each probe, generates a minimal class:
 * <pre>
 * {@literal @}Name("spice.probe.df3970513497")
 * {@literal @}Label("DESCipher init")
 * {@literal @}StackTrace(true)
 * public class SpiceEvent_df3970513497 extends jdk.jfr.Event {}
 * </pre>
 *
 * <p>The generated classes are packaged into a temp JAR and loaded via
 * {@link Instrumentation#appendToBootstrapClassLoaderSearch}.
 */
public class EventClassGenerator {

    private static final String JFR_EVENT_INTERNAL = "jdk/jfr/Event";
    private static final String JFR_NAME_DESC = "Ljdk/jfr/Name;";
    private static final String JFR_LABEL_DESC = "Ljdk/jfr/Label;";
    private static final String JFR_STACK_TRACE_DESC = "Ljdk/jfr/StackTrace;";
    private static final String JFR_ENABLED_DESC = "Ljdk/jfr/Enabled;";

    /** Package for generated event classes. */
    static final String EVENT_PACKAGE = "io/spicelabs/ancho/events";

    /**
     * Generate event classes for all probes and make them loadable.
     *
     * @param probes the probe definitions
     * @param inst   the Instrumentation instance (for bootstrap classloader injection)
     * @return map from probe ID → fully qualified class name of the generated event
     */
    public static Map<String, String> generateAndLoad(List<ProbeConfig.Probe> probes,
                                                       Instrumentation inst) throws IOException {
        Map<String, String> eventClassNames = new HashMap<>();
        Map<String, byte[]> classBytes = new HashMap<>();

        for (ProbeConfig.Probe probe : probes) {
            String safeName = probe.id.replace('.', '_').replace('-', '_');
            String internalName = EVENT_PACKAGE + "/" + safeName;
            String fqn = internalName.replace('/', '.');

            byte[] bytes = generateEventClass(internalName, probe.id, probe.label);
            classBytes.put(internalName + ".class", bytes);
            eventClassNames.put(probe.id, fqn);
        }

        if (!classBytes.isEmpty()) {
            File tempJar = createTempJar(classBytes);
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tempJar));
            SpiceAgent.log("Loaded " + classBytes.size() + " event classes from " + tempJar);
        }

        return eventClassNames;
    }

    /**
     * Generate bytecode for a single JFR event class.
     */
    static byte[] generateEventClass(String internalName, String eventName, String label) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        // Class header: public class <name> extends jdk.jfr.Event
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, JFR_EVENT_INTERNAL, null);

        // @Name("spice.probe.xxx")
        AnnotationVisitor av = cw.visitAnnotation(JFR_NAME_DESC, true);
        av.visit("value", eventName);
        av.visitEnd();

        // @Label("DESCipher init")
        av = cw.visitAnnotation(JFR_LABEL_DESC, true);
        av.visit("value", label);
        av.visitEnd();

        // @StackTrace(true)
        av = cw.visitAnnotation(JFR_STACK_TRACE_DESC, true);
        av.visit("value", true);
        av.visitEnd();

        // @Enabled(true) — required for events to fire without explicit JFC configuration
        av = cw.visitAnnotation(JFR_ENABLED_DESC, true);
        av.visit("value", true);
        av.visitEnd();

        // Default constructor: public <init>() { super(); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, JFR_EVENT_INTERNAL, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Package class bytes into a temporary JAR file.
     */
    private static File createTempJar(Map<String, byte[]> classBytes) throws IOException {
        File tempJar = File.createTempFile("spice-events-", ".jar");
        tempJar.deleteOnExit();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest)) {
            for (Map.Entry<String, byte[]> entry : classBytes.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }

        return tempJar;
    }
}
