package dev.skillsgateway.server.storage.objectstore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Everything one repository is, as one small object in the bucket.
 *
 * <p>This is the whole state a reader needs: the reference map, the packs those references reach
 * through, and the write-ahead sequence the state reflects. Keeping it here rather than in a
 * database is what makes a bucket a complete, self-describing repository and therefore a replica
 * disposable — and it is also the auditability of the guarantee the product rests on, because
 * "what is this marketplace serving right now" is one {@code GetObject} and a line of JSON rather
 * than a decode of a stack of binary blocks.
 *
 * <p>Every field is immutable and every mutator returns a new instance: an instance is a version,
 * and a version is what a conditional write swaps from.
 *
 * @param version the manifest schema version, so a future layout change is detectable rather than
 *     misread
 * @param sequence the write-ahead sequence this state reflects; every accepted transition
 *     increments it, so a manifest and its write-ahead entry name each other
 * @param refs reference name to value — a 40-character object id, or {@code ref: <target>} for a
 *     symbolic reference such as {@code HEAD}. Symbolic {@code HEAD} lives here rather than in an
 *     object of its own, which is what makes repository creation a single create-exactly-once write
 * @param packs the live packs, content-named and immutable
 * @param tombstones packs no live manifest references any more, and when they stopped being
 *     referenced. They are not deleted at that moment: a replica part-way through streaming one
 *     would get a 404 in the middle of a fetch, so deletion waits out a grace period
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepositoryManifest(
        int version,
        long sequence,
        SortedMap<String, String> refs,
        List<PackEntry> packs,
        Map<String, Long> tombstones) {

    /** The only schema version so far. */
    public static final int CURRENT_VERSION = 1;

    /** Prefix marking a symbolic reference value, the same spelling git uses on disk. */
    public static final String SYMBOLIC_PREFIX = "ref: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One pack, described exactly as JGit's {@code DfsPackDescription} needs it back.
     *
     * @param name the content-independent unique pack name; the objects are stored under it
     * @param source which JGit activity produced the pack
     * @param sizes file size per pack extension, keyed by the extension's own name
     * @param blockSizes block size per pack extension, where one was set
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackEntry(
            String name,
            String source,
            long objectCount,
            long deltaCount,
            int indexVersion,
            long minUpdateIndex,
            long maxUpdateIndex,
            Map<String, Long> sizes,
            Map<String, Integer> blockSizes) {

        public PackEntry {
            sizes = sizes == null ? Map.of() : Map.copyOf(sizes);
            blockSizes = blockSizes == null ? Map.of() : Map.copyOf(blockSizes);
        }
    }

    public RepositoryManifest {
        refs = refs == null ? new TreeMap<>() : new TreeMap<>(refs);
        packs = packs == null ? List.of() : List.copyOf(packs);
        tombstones = tombstones == null ? Map.of() : Map.copyOf(tombstones);
    }

    /** A brand-new repository: no objects, and a head pointing at the branch the gateway serves. */
    public static RepositoryManifest created(String headTarget) {
        SortedMap<String, String> refs = new TreeMap<>();
        refs.put(org.eclipse.jgit.lib.Constants.HEAD, SYMBOLIC_PREFIX + headTarget);
        return new RepositoryManifest(CURRENT_VERSION, 0L, refs, List.of(), Map.of());
    }

    public static RepositoryManifest parse(byte[] json) throws IOException {
        RepositoryManifest manifest = MAPPER.readValue(json, RepositoryManifest.class);
        if (manifest.version() != CURRENT_VERSION) {
            throw new IOException("unsupported repository manifest version %d (this gateway writes %d)"
                    .formatted(manifest.version(), CURRENT_VERSION));
        }
        return manifest;
    }

    public byte[] toJson() throws IOException {
        return MAPPER.writeValueAsBytes(this);
    }

    /** The value of a reference, or null when it is not there. */
    public String ref(String name) {
        return refs.get(name);
    }

    /** This manifest with one reference set, at the next sequence. */
    public RepositoryManifest withRef(String name, String value) {
        SortedMap<String, String> next = new TreeMap<>(refs);
        next.put(name, value);
        return new RepositoryManifest(version, sequence + 1, next, packs, tombstones);
    }

    /** This manifest with a set of reference edits applied as one step; a null value removes. */
    public RepositoryManifest withRefs(Map<String, String> edits) {
        SortedMap<String, String> next = new TreeMap<>(refs);
        edits.forEach((name, value) -> {
            if (value == null) {
                next.remove(name);
            } else {
                next.put(name, value);
            }
        });
        return new RepositoryManifest(version, sequence + 1, next, packs, tombstones);
    }

    /** This manifest with one reference removed, at the next sequence. */
    public RepositoryManifest withoutRef(String name) {
        SortedMap<String, String> next = new TreeMap<>(refs);
        next.remove(name);
        return new RepositoryManifest(version, sequence + 1, next, packs, tombstones);
    }

    /**
     * This manifest with packs added and, optionally, packs replaced. A replaced pack is
     * tombstoned rather than forgotten, so its objects can be deleted later — once no fetch can
     * still be streaming them — rather than immediately.
     */
    public RepositoryManifest withPacks(List<PackEntry> added, List<String> replaced, long now) {
        Map<String, PackEntry> live = new LinkedHashMap<>();
        for (PackEntry pack : packs) {
            live.put(pack.name(), pack);
        }
        Map<String, Long> graves = new HashMap<>(tombstones);
        for (String name : replaced) {
            if (live.remove(name) != null) {
                graves.put(name, now);
            }
        }
        for (PackEntry pack : added) {
            live.put(pack.name(), pack);
            graves.remove(pack.name());
        }
        return new RepositoryManifest(version, sequence + 1, refs, List.copyOf(live.values()), Map.copyOf(graves));
    }

    /** This manifest with the named tombstones forgotten, their objects having been deleted. */
    public RepositoryManifest withoutTombstones(List<String> names) {
        Map<String, Long> graves = new HashMap<>(tombstones);
        names.forEach(graves::remove);
        return new RepositoryManifest(version, sequence + 1, refs, packs, Map.copyOf(graves));
    }
}
