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
 * Injects {@link ProbeAdvice} and {@link ClassGitoidRegistry} onto the bootstrap classloader.
 *
 * <p>ByteBuddy Advice is inlined into the target method's bytecode. When the
 * target is a JDK bootstrap class (e.g., {@code javax.crypto.Cipher}), the
 * inlined code must reference classes that are visible from the bootstrap
 * classloader. {@code ProbeAdvice}, its static maps, and the {@code ClassGitoidRegistry}
 * it reads must therefore be on the bootstrap classpath.
 *
 * <p>We achieve this by packaging those classes into a temp JAR and using
 * {@link Instrumentation#appendToBootstrapClassLoaderSearch}.
 */
public class BootstrapInjector {

    /**
     * Compiled classes that must live on the bootstrap classloader. Include nested classes
     * explicitly — each compiles to its own {@code Name$Nested.class} file.
     */
    static final String[] BOOTSTRAP_CLASS_RESOURCES = {
            "io/spicelabs/ancho/ProbeAdvice.class",
            "io/spicelabs/ancho/ProbeAdvice$EventHandles.class",
            "io/spicelabs/ancho/ClassGitoidRegistry.class",
            "io/spicelabs/ancho/ClassGitoidRegistry$LoaderKey.class",
            "io/spicelabs/ancho/CallerGitoidWalker.class",
    };

    /**
     * Copy the bootstrap helper classes from our own classloader into a temp JAR
     * and inject them onto the bootstrap classloader.
     */
    public static void inject(Instrumentation inst) throws IOException {
        File tempJar = File.createTempFile("spice-advice-", ".jar");
        tempJar.deleteOnExit();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        int written = 0;
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest)) {
            for (String resource : BOOTSTRAP_CLASS_RESOURCES) {
                byte[] classBytes = loadClassBytes(resource);
                if (classBytes == null) {
                    SpiceAgent.log("WARN: Could not load " + resource + " for bootstrap injection");
                    continue;
                }
                jos.putNextEntry(new JarEntry(resource));
                jos.write(classBytes);
                jos.closeEntry();
                written++;
            }
        }

        if (written == 0) {
            return;
        }
        inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tempJar));
        SpiceAgent.log("Injected ProbeAdvice + ClassGitoidRegistry onto bootstrap classloader");
    }

    private static byte[] loadClassBytes(String resource) throws IOException {
        try (InputStream is = BootstrapInjector.class.getClassLoader()
                .getResourceAsStream(resource)) {
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
