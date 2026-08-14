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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps each loaded class's runtime identity to the gitoid {@link ClassHashTransformer}
 * computed from its original (pre-instrumentation) bytes, so {@link ProbeAdvice} can stamp
 * each probe event with the gitoid of the class it fired in.
 *
 * <p><b>Weak loader keys, lock-free.</b> Entries are grouped by the actual {@code ClassLoader}
 * in a {@link ConcurrentHashMap} whose keys are <em>weak references</em> to the loader. When a
 * loader becomes unreachable its key is enqueued and its whole sub-map expunged — the registry
 * never pins a loader or class (so class unloading proceeds), and there is no
 * {@code identityHashCode}-reuse hazard. Reads and writes are lock-free, so the per-probe
 * {@link #lookup} hot path takes no global lock. The inner maps hold only Strings
 * (name → gitoid), so they never reference the loader back.
 *
 * <p><b>Bootstrap residency.</b> This class is injected onto the bootstrap classloader
 * (see {@link BootstrapInjector}) so the inlined {@link ProbeAdvice} can reach it even from
 * instrumented JDK classes. {@link ClassHashTransformer} runs on the system classloader and
 * therefore writes this map <em>reflectively against the bootstrap copy</em> (forcing it via
 * {@code Class.forName(name, true, null)}) so writer and reader share one map — the same
 * duplicate-class hazard {@link ProbeInstaller} already guards against for {@link ProbeAdvice}.
 */
public final class ClassGitoidRegistry {

    private static final ConcurrentHashMap<LoaderKey, ConcurrentHashMap<String, String>> BY_LOADER =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<ClassLoader> DEAD_LOADERS = new ReferenceQueue<>();

    private ClassGitoidRegistry() {
    }

    /** Record the gitoid for a loaded class. No-op if loader, name, or gitoid is null. */
    public static void register(ClassLoader loader, String binaryName, String gitoid) {
        if (loader == null || binaryName == null || gitoid == null) {
            return;
        }
        expungeDeadLoaders();
        ConcurrentHashMap<String, String> byName = BY_LOADER.get(new LoaderKey(loader, null));
        if (byName == null) {
            // Only the inserted key is registered with the queue (so dead loaders are expunged).
            ConcurrentHashMap<String, String> created = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> prior =
                    BY_LOADER.putIfAbsent(new LoaderKey(loader, DEAD_LOADERS), created);
            byName = prior != null ? prior : created;
        }
        byName.put(binaryName, gitoid);
    }

    /** The gitoid recorded for a class, or null if it wasn't hashed (e.g. JDK/bootstrap). */
    public static String lookup(ClassLoader loader, String binaryName) {
        if (loader == null || binaryName == null) {
            return null;
        }
        ConcurrentHashMap<String, String> byName = BY_LOADER.get(new LoaderKey(loader, null));
        return byName == null ? null : byName.get(binaryName);
    }

    /** Drop sub-maps whose classloader has been collected. */
    private static void expungeDeadLoaders() {
        Reference<? extends ClassLoader> dead;
        while ((dead = DEAD_LOADERS.poll()) != null) {
            BY_LOADER.remove(dead);
        }
    }

    /** Weak reference to a classloader with identity-based equals/hashCode for use as a map key. */
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;

        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue);
            this.hash = System.identityHashCode(loader);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true; // identity match — also how a collected key removes its own entry
            }
            if (!(o instanceof LoaderKey)) {
                return false;
            }
            ClassLoader self = get();
            return self != null && self == ((LoaderKey) o).get();
        }
    }
}
