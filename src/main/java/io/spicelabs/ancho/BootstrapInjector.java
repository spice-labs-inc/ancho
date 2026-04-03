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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Injects the {@link ProbeAdvice} class onto the bootstrap classloader.
 *
 * <p>ByteBuddy Advice is inlined into the target method's bytecode. When the
 * target is a JDK bootstrap class (e.g., {@code javax.crypto.Cipher}), the
 * inlined code must reference classes that are visible from the bootstrap
 * classloader. {@code ProbeAdvice} and its static maps must therefore be on
 * the bootstrap classpath.
 *
 * <p>We achieve this by packaging ProbeAdvice.class into a temp JAR and using
 * {@link Instrumentation#appendToBootstrapClassLoaderSearch}.
 */
public class BootstrapInjector {

    private static final String ADVICE_CLASS_RESOURCE = "io/spicelabs/ancho/ProbeAdvice.class";

    /**
     * Copy ProbeAdvice.class from our own classloader into a temp JAR
     * and inject it onto the bootstrap classloader.
     */
    public static void inject(Instrumentation inst) throws IOException {
        byte[] classBytes = loadClassBytes();
        if (classBytes == null) {
            SpiceAgent.log("WARN: Could not load ProbeAdvice.class for bootstrap injection");
            return;
        }

        File tempJar = File.createTempFile("spice-advice-", ".jar");
        tempJar.deleteOnExit();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest)) {
            jos.putNextEntry(new JarEntry(ADVICE_CLASS_RESOURCE));
            jos.write(classBytes);
            jos.closeEntry();
        }

        inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tempJar));
        SpiceAgent.log("Injected ProbeAdvice onto bootstrap classloader");
    }

    private static byte[] loadClassBytes() throws IOException {
        try (InputStream is = BootstrapInjector.class.getClassLoader()
                .getResourceAsStream(ADVICE_CLASS_RESOURCE)) {
            if (is == null) return null;
            return readAllBytes(is);
        }
    }

    // JDK 8 compatible — no InputStream.readAllBytes()
    private static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buf = new byte[4096];
        int len;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }
}
