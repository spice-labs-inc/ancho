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

import java.io.StringReader;

import org.junit.jupiter.api.Test;

class ProbeConfigTest {

    @Test
    void parse_validConfig() {
        String json = "{\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"probes\": [\n" +
                "    {\n" +
                "      \"id\": \"spice.probe.df3970513497\",\n" +
                "      \"class\": \"com.sun.crypto.provider.DESCipher\",\n" +
                "      \"method\": \"init\",\n" +
                "      \"descriptor\": \"(ILjava/security/Key;)V\",\n" +
                "      \"label\": \"DESCipher init\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"spice.probe.abc123456789\",\n" +
                "      \"class\": \"javax.crypto.Cipher\",\n" +
                "      \"method\": \"getInstance\",\n" +
                "      \"descriptor\": \"(Ljava/lang/String;)Ljavax/crypto/Cipher;\",\n" +
                "      \"label\": \"Cipher getInstance\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        ProbeConfig config = ProbeConfig.parse(new StringReader(json));

        assertEquals(2, config.getProbes().size());
        assertEquals(2, config.getByClass().size());

        assertTrue(config.hasClass("com.sun.crypto.provider.DESCipher"));
        assertTrue(config.hasClass("javax.crypto.Cipher"));
        assertFalse(config.hasClass("com.example.NotInstrumented"));

        ProbeConfig.Probe first = config.getProbes().get(0);
        assertEquals("spice.probe.df3970513497", first.id);
        assertEquals("com.sun.crypto.provider.DESCipher", first.className);
        assertEquals("init", first.method);
        assertEquals("(ILjava/security/Key;)V", first.descriptor);
        assertEquals("DESCipher init", first.label);
    }

    @Test
    void parse_multipleMethodsSameClass() {
        String json = "{\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"probes\": [\n" +
                "    { \"id\": \"p1\", \"class\": \"javax.crypto.Cipher\", \"method\": \"init\", \"descriptor\": \"()V\" },\n" +
                "    { \"id\": \"p2\", \"class\": \"javax.crypto.Cipher\", \"method\": \"getInstance\", \"descriptor\": \"(Ljava/lang/String;)V\" },\n" +
                "    { \"id\": \"p3\", \"class\": \"javax.crypto.Cipher\", \"method\": \"doFinal\", \"descriptor\": \"([B)[B\" }\n" +
                "  ]\n" +
                "}";

        ProbeConfig config = ProbeConfig.parse(new StringReader(json));

        assertEquals(3, config.getProbes().size());
        assertEquals(1, config.getByClass().size());
        assertEquals(3, config.getProbesForClass("javax.crypto.Cipher").size());
    }

    @Test
    void parse_emptyProbes() {
        String json = "{ \"version\": \"1.0.0\", \"probes\": [] }";
        ProbeConfig config = ProbeConfig.parse(new StringReader(json));
        assertEquals(0, config.getProbes().size());
        assertTrue(config.getByClass().isEmpty());
    }

    @Test
    void parse_nullProbes() {
        String json = "{ \"version\": \"1.0.0\" }";
        ProbeConfig config = ProbeConfig.parse(new StringReader(json));
        assertEquals(0, config.getProbes().size());
    }

    @Test
    void parse_skipsEntriesWithMissingFields() {
        String json = "{\n" +
                "  \"probes\": [\n" +
                "    { \"id\": \"p1\", \"class\": null, \"method\": \"init\" },\n" +
                "    { \"id\": \"p2\", \"class\": \"Foo\", \"method\": null },\n" +
                "    { \"id\": \"p3\", \"class\": \"Bar\", \"method\": \"run\", \"descriptor\": \"()V\" }\n" +
                "  ]\n" +
                "}";

        ProbeConfig config = ProbeConfig.parse(new StringReader(json));
        assertEquals(1, config.getProbes().size());
        assertEquals("Bar", config.getProbes().get(0).className);
    }

    @Test
    void parse_defaultsLabel() {
        String json = "{ \"probes\": [{ \"id\": \"p1\", \"class\": \"Foo\", \"method\": \"bar\" }] }";
        ProbeConfig config = ProbeConfig.parse(new StringReader(json));
        assertEquals("Foo bar", config.getProbes().get(0).label);
    }

    @Test
    void getProbesForClass_unknownClass() {
        String json = "{ \"probes\": [{ \"id\": \"p1\", \"class\": \"Foo\", \"method\": \"bar\" }] }";
        ProbeConfig config = ProbeConfig.parse(new StringReader(json));
        assertTrue(config.getProbesForClass("Unknown").isEmpty());
    }
}
