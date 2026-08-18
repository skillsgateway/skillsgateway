package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic license detection over a pinned snapshot (GW_0093): SPDX identifiers resolved from
 * license/copying files anywhere in the tree, {@code SPDX-License-Identifier} tags inside them, and
 * the license metadata fields of the marketplace manifest. Exact fingerprint matching only — no
 * scoring, no thresholds — so the same content under the same {@link #VERSION} always yields the
 * same detections; a source that identifies nothing is an explicit unknown, never a guess.
 */
final class LicenseDetector {

    /** Identity of the fingerprint table; bump on any change to what this class recognizes. */
    static final String VERSION = "1";

    static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** File names (case-insensitive, optional single extension) that carry a repository's license. */
    private static final Pattern LICENSE_FILE =
            Pattern.compile("(?i)(?:^|/)(license|licence|copying|copying3|unlicense)(?:\\.[a-z0-9]+)?$");

    /** A file's own declaration wins outright over fingerprinting its prose. */
    private static final Pattern SPDX_TAG = Pattern.compile("SPDX-License-Identifier:\\s*([A-Za-z0-9.+-]+)");

    /**
     * The fingerprint table: a distinctive phrase of each canonical license text, matched as a
     * substring of the lowercased, whitespace-collapsed file. Order matters where one license's
     * phrase could shadow another's (BSD-3 before BSD-2, CC-BY-SA before CC-BY), so this is a list,
     * not a map.
     */
    private static final List<Map.Entry<String, String>> FINGERPRINTS = List.of(
            Map.entry("AGPL-3.0", "gnu affero general public license version 3"),
            Map.entry("LGPL-3.0", "gnu lesser general public license version 3"),
            Map.entry("LGPL-2.1", "gnu lesser general public license version 2.1"),
            Map.entry("GPL-3.0", "gnu general public license version 3"),
            Map.entry("GPL-2.0", "gnu general public license version 2"),
            Map.entry(
                    "MIT",
                    "permission is hereby granted, free of charge, to any person obtaining a copy of this software"),
            Map.entry("Apache-2.0", "apache license version 2.0"),
            Map.entry("BSD-3-Clause", "neither the name of"),
            Map.entry(
                    "BSD-2-Clause",
                    "redistribution and use in source and binary forms, with or without modification, are permitted"),
            Map.entry("ISC", "permission to use, copy, modify, and/or distribute this software for any purpose"),
            Map.entry("MPL-2.0", "mozilla public license version 2.0"),
            Map.entry("EPL-2.0", "eclipse public license - v 2.0"),
            Map.entry("Unlicense", "this is free and unencumbered software released into the public domain"),
            Map.entry("CC0-1.0", "cc0 1.0 universal"),
            Map.entry("CC-BY-SA-4.0", "creative commons attribution-sharealike 4.0"),
            Map.entry("CC-BY-4.0", "creative commons attribution 4.0"));

    /** Canonical ids, for normalising manifest-declared values case-insensitively. */
    private static final Map<String, String> KNOWN_IDS = FINGERPRINTS.stream()
            .collect(java.util.stream.Collectors.toMap(
                    entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getKey));

    private LicenseDetector() {}

    /** Where a detection came from. */
    enum Source {
        FILE,
        MANIFEST
    }

    /**
     * One license detection.
     *
     * @param source license file or manifest metadata field
     * @param location the file path, or {@code <manifest path>#<field>} for manifest metadata
     * @param spdxId the identified SPDX id, or {@code null} for the unknown-license state
     * @param declared the raw declared value for manifest metadata, {@code null} for files
     */
    record Detection(Source source, String location, String spdxId, String declared) {

        boolean unknown() {
            return spdxId == null;
        }
    }

    /** Every license detection in the snapshot, in tree order; empty means no license information. */
    @io.github.reqstool.annotations.Requirements({"GW_0089"})
    static List<Detection> detect(SnapshotUnderVetting snapshot) throws IOException {
        List<Detection> detections = new ArrayList<>();
        snapshot.walk((path, content) -> {
            if (MANIFEST_PATH.equals(path)) {
                detections.addAll(manifestDetections(path, content));
                return;
            }
            if (!LICENSE_FILE.matcher(path).find()) {
                return;
            }
            // An oversized or binary license file identifies nothing — explicitly unknown, never
            // silently skipped: fail-closed is the whole point of the unknown state.
            detections.add(fileDetection(path, ContentRules.text(content)));
        });
        return List.copyOf(detections);
    }

    /**
     * Identifies one license file: the file's own SPDX tag wins outright (normalised against the
     * known table; an unrecognised tag is the unknown state carrying the raw declared id), then the
     * fingerprint table over the prose. Nothing matching is the unknown-license state.
     */
    private static Detection fileDetection(String path, String text) {
        if (text == null || text.isBlank()) {
            return new Detection(Source.FILE, path, null, null);
        }
        Matcher tag = SPDX_TAG.matcher(text);
        if (tag.find()) {
            String declared = tag.group(1);
            return new Detection(Source.FILE, path, KNOWN_IDS.get(declared.toLowerCase(Locale.ROOT)), declared);
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Map.Entry<String, String> fingerprint : FINGERPRINTS) {
            if (normalized.contains(fingerprint.getValue())) {
                return new Detection(Source.FILE, path, fingerprint.getKey(), null);
            }
        }
        return new Detection(Source.FILE, path, null, null);
    }

    /**
     * The manifest's license metadata: the top-level {@code license} field, a {@code
     * metadata.license} field, and each plugin's {@code license} field. A declared value is taken
     * as an SPDX id (case-normalised against the known table); one that names no known id is an
     * unknown detection carrying the raw declared value.
     */
    private static List<Detection> manifestDetections(String path, byte[] content) {
        String text = ContentRules.text(content);
        if (text == null) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (IOException e) {
            // An unparseable manifest is the manifest policy's finding, not a license detection.
            return List.of();
        }
        List<Detection> detections = new ArrayList<>();
        addDeclared(detections, path + "#license", root.path("license"));
        addDeclared(
                detections, path + "#metadata.license", root.path("metadata").path("license"));
        for (JsonNode plugin : root.path("plugins")) {
            String name = plugin.path("name").asText("?");
            addDeclared(detections, "%s#plugins[%s].license".formatted(path, name), plugin.path("license"));
        }
        return detections;
    }

    private static void addDeclared(List<Detection> detections, String location, JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            return;
        }
        String declared = value.asText().trim();
        String spdxId = KNOWN_IDS.get(declared.toLowerCase(Locale.ROOT));
        detections.add(new Detection(Source.MANIFEST, location, spdxId, declared));
    }
}
