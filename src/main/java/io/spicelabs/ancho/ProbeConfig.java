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

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the probe config JSON and provides a lookup from class name
 * to the list of methods that should be instrumented.
 */
public class ProbeConfig {

    private final List<Probe> probes;
    /** class FQN (dot-separated) → list of probes for that class */
    private final Map<String, List<Probe>> byClass;

    public ProbeConfig(List<Probe> probes) {
        this.probes = Collections.unmodifiableList(new ArrayList<>(probes));
        Map<String, List<Probe>> map = new HashMap<>();
        for (Probe p : probes) {
            map.computeIfAbsent(p.className, k -> new ArrayList<>()).add(p);
        }
        this.byClass = Collections.unmodifiableMap(map);
    }

    public List<Probe> getProbes() {
        return probes;
    }

    public Map<String, List<Probe>> getByClass() {
        return byClass;
    }

    /** Check if a class (dot-separated FQN) has any probes. */
    public boolean hasClass(String classFqn) {
        return byClass.containsKey(classFqn);
    }

    /** Get probes for a class (dot-separated FQN). */
    public List<Probe> getProbesForClass(String classFqn) {
        return byClass.getOrDefault(classFqn, Collections.emptyList());
    }

    /** A single probe definition from the config JSON. */
    public static class Probe {
        public final String id;
        public final String className;
        public final String method;
        public final String descriptor;
        public final String label;

        public Probe(String id, String className, String method, String descriptor, String label) {
            this.id = id;
            this.className = className;
            this.method = method;
            this.descriptor = descriptor;
            this.label = label;
        }

        @Override
        public String toString() {
            return id + " → " + className + "." + method + descriptor;
        }
    }

    // ── JSON parsing ────────────────────────────────────────────────────

    /** JSON root structure. */
    private static class ConfigJson {
        String version;
        List<ProbeJson> probes;
    }

    /** JSON probe entry. */
    private static class ProbeJson {
        String id;
        @SerializedName("class") String className;
        String method;
        String descriptor;
        String label;
    }

    /**
     * Parse a probe config JSON file.
     *
     * @param path path to the JSON file
     * @return parsed ProbeConfig
     */
    public static ProbeConfig load(String path) throws IOException {
        Path p = Paths.get(path);
        try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /**
     * Parse probe config from a Reader (for testing).
     */
    public static ProbeConfig parse(Reader reader) {
        Gson gson = new Gson();
        ConfigJson json = gson.fromJson(reader, ConfigJson.class);

        List<Probe> probes = new ArrayList<>();
        if (json.probes != null) {
            for (ProbeJson pj : json.probes) {
                if (pj.className == null || pj.method == null) continue;
                probes.add(new Probe(
                        pj.id != null ? pj.id : "spice.probe.unknown",
                        pj.className,
                        pj.method,
                        pj.descriptor,
                        pj.label != null ? pj.label : pj.className + " " + pj.method
                ));
            }
        }
        return new ProbeConfig(probes);
    }
}
