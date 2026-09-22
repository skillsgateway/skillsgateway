package dev.skillsgateway.server.storage;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Component;

/**
 * The commit a marketplace's facade is serving right now (GW_INGEST_0033).
 *
 * <p>Read from the published repository's served reference — the same read the facade itself
 * serves from, so no answer here can disagree with what a {@code git fetch} returns. Empty means
 * the marketplace is serving nothing: never published, revoked, or unpublished.
 *
 * <p>This is deliberately <em>not</em> derivable from snapshot state. Which snapshots are recorded
 * approved and which commit consumers receive are different questions, and they diverge exactly
 * where it matters — a retraction leaves approved rows behind while the facade serves nothing, so
 * a reader that inferred the answer from the newest approved row would name content nobody can
 * fetch, at the moment someone is trying to understand the retraction.
 *
 * <p>One place, because there were already two: the adoption report and this. A third copy of a
 * ref read is a third chance for them to answer differently.
 */
@Component
public class ServedTip {

    private final GitStorage storage;

    public ServedTip(GitStorage storage) {
        this.storage = storage;
    }

    /** The served commit of one marketplace, or empty when it serves nothing. */
    @Requirements({"GW_INGEST_0033"})
    public Optional<String> of(String marketplace) {
        try {
            Optional<Repository> serving = storage.publishedIfServing(marketplace);
            if (serving.isEmpty()) {
                return Optional.empty();
            }
            try (Repository repository = serving.get()) {
                return Optional.ofNullable(repository.resolve(GitStorage.SERVED_REF))
                        .map(ObjectId::name);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
