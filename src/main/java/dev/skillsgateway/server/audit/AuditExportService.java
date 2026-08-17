package dev.skillsgateway.server.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cursor-based export of the append-only ledger to registered sinks.
 *
 * <p>A sink owns nothing but its position in the ledger. A pass reads the entries after that
 * position, hands them to the lifecycle webhook delivery machinery as one signed delivery, and only
 * then advances the position — a crash in between re-sends the batch instead of skipping it, which
 * is exactly the at-least-once guarantee of GW_0028. Replay (GW_0029) is the same column written
 * backwards.
 */
@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);

    /** Rows held in memory at once while streaming; the requested page size may be far larger. */
    private static final int CHUNK_SIZE = 500;

    /** Exported payloads are plain text and numbers, so no java.time modules are involved. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FetchLogRepository fetchLogRepository;
    private final AuditSinkRepository sinkRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriberRepository subscriberRepository;
    private final WebhookService webhookService;
    private final SkillsGatewayProperties.AuditExport properties;

    public AuditExportService(
            FetchLogRepository fetchLogRepository,
            AuditSinkRepository sinkRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookSubscriberRepository subscriberRepository,
            WebhookService webhookService,
            SkillsGatewayProperties properties) {
        this.fetchLogRepository = fetchLogRepository;
        this.sinkRepository = sinkRepository;
        this.deliveryRepository = deliveryRepository;
        this.subscriberRepository = subscriberRepository;
        this.webhookService = webhookService;
        this.properties = properties.auditExport();
    }

    /** A freshly created sink; the only time the signing secret of its channel is ever returned. */
    @Schema(description = "A newly registered audit export sink, with its show-once signing secret")
    public record CreatedSink(
            @Schema(description = "Sink id") long id,
            @Schema(description = "Sink name") String name,
            @Schema(description = "Sink kind") String kind,

            @Schema(description = "Target URL batches are POSTed to")
            String url,

            @Schema(description = "Ledger sequence the sink starts after")
            long cursorPosition,

            @Schema(description = "Maximum ledger entries per batch")
            int batchSize,

            @Schema(description = "Signing secret of the sink's delivery channel - shown exactly once")
            String secret,

            @Schema(description = "Creation time") Instant createdAt) {}

    /** One exported batch: what was sent, from where to where, and the ledger entries themselves. */
    @Schema(description = "Audit ledger export batch payload")
    public record AuditBatch(
            @Schema(description = "Always audit.export") String event,

            @Schema(description = "Sink the batch was produced for")
            String sink,

            @Schema(description = "Batch creation time, ISO-8601")
            String exportedAt,

            @Schema(description = "Ledger sequence the batch starts after")
            long fromCursor,

            @Schema(description = "Ledger sequence of the last entry in the batch")
            long toCursor,

            @Schema(description = "Number of entries in the batch")
            int count,

            @Schema(description = "The ledger entries, in ledger order")
            List<FetchLogRepository.AuditEntry> entries) {}

    /**
     * Registers a webhook sink: an ordinary webhook subscriber filtered to {@code audit.export} plus
     * the cursor row that points at it. Nothing about signing, retry, or delivery recording is
     * re-implemented here.
     */
    @Requirements({"GW_0028"})
    public CreatedSink createWebhookSink(String name, String url, long cursorPosition, int batchSize) {
        WebhookService.CreatedSubscriber subscriber =
                webhookService.createSubscriber(name, url, WebhookEvent.AUDIT_EXPORT);
        AuditSink sink =
                sinkRepository.create(name, AuditSink.WEBHOOK, subscriber.id(), Math.max(cursorPosition, 0), batchSize);
        return new CreatedSink(
                sink.id(),
                sink.name(),
                sink.kind(),
                subscriber.url(),
                sink.cursorPosition(),
                sink.batchSize(),
                subscriber.secret(),
                sink.createdAt());
    }

    public List<AuditSink> listSinks() {
        return sinkRepository.list();
    }

    public Optional<AuditSink> findSink(long id) {
        return sinkRepository.findById(id);
    }

    public Optional<AuditSink> findSinkByName(String name) {
        return sinkRepository.findByName(name);
    }

    /** A sink's delivery channel is a webhook subscriber, so the two name spaces are one. */
    public boolean channelNameTaken(String name) {
        return webhookService.findSubscriber(name).isPresent();
    }

    /** Target URL of a sink's delivery channel; never its secret. */
    public String sinkUrl(AuditSink sink) {
        return subscriberRepository
                .findById(sink.subscriberId())
                .map(subscriber -> subscriber.url())
                .orElse(null);
    }

    /** Removing the subscriber cascades the sink row, so a sink never outlives its channel. */
    public boolean deleteSink(long id) {
        Optional<AuditSink> sink = sinkRepository.findById(id);
        if (sink.isEmpty()) {
            return false;
        }
        subscriberRepository.delete(sink.get().subscriberId());
        sinkRepository.delete(id);
        return true;
    }

    /**
     * Sets a sink's position; setting it back re-delivers everything after it on the next pass.
     * Replay is deliberately a cursor write rather than a separate delivery mode (GW_0029).
     */
    @Requirements({"GW_0029"})
    public Optional<AuditSink> resetCursor(long id, long cursorPosition) {
        return sinkRepository.updateCursor(id, Math.max(cursorPosition, 0));
    }

    /** Highest ledger sequence written so far — what a sink's cursor is compared against. */
    public long ledgerHead() {
        return fetchLogRepository.maxId();
    }

    /** Entries a pull consumer sees next, under the same settling cutoff the sinks use. */
    public List<FetchLogRepository.AuditEntry> entriesAfter(long cursor, int limit) {
        return fetchLogRepository.entriesAfter(cursor, cutoff(), limit);
    }

    /**
     * The sequence a pull consumer resumes from after this page — resolved before anything is
     * written so it can be announced in a response header rather than a trailer.
     */
    public long exportEndCursor(long after, int limit) {
        return fetchLogRepository.cursorAfter(after, cutoff(), limit);
    }

    /**
     * Streams the ledger entries in {@code (after, endCursor]} as newline-delimited JSON, one
     * compact object per line in ledger order (GW_0027). The ledger is read in fixed chunks, so
     * memory is bounded by the chunk rather than by the requested page size.
     */
    @Requirements({"GW_0027"})
    public void streamNdjson(long after, long endCursor, OutputStream out) throws IOException {
        Instant cutoff = cutoff();
        long position = after;
        while (position < endCursor) {
            List<FetchLogRepository.AuditEntry> chunk = fetchLogRepository.entriesAfter(position, cutoff, CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return;
            }
            StringBuilder lines = new StringBuilder();
            for (FetchLogRepository.AuditEntry entry : chunk) {
                if (entry.id() > endCursor) {
                    break;
                }
                lines.append(serialize(entry)).append('\n');
                position = entry.id();
            }
            out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (chunk.getLast().id() >= endCursor) {
                return;
            }
        }
    }

    /** One export pass over every enabled sink; returns the number of batches enqueued. */
    public int exportPass() {
        int enqueued = 0;
        for (AuditSink sink : sinkRepository.listEnabled()) {
            if (exportBatch(sink).isPresent()) {
                enqueued++;
            }
        }
        return enqueued;
    }

    /**
     * Hands one sink the next batch after its cursor. The delivery row is durable before the cursor
     * moves: a crash in between costs a duplicate, never a gap (GW_0028).
     */
    @Requirements({"GW_0028"})
    public Optional<WebhookDelivery> exportBatch(AuditSink sink) {
        List<FetchLogRepository.AuditEntry> entries =
                fetchLogRepository.entriesAfter(sink.cursorPosition(), cutoff(), sink.batchSize());
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        long to = entries.getLast().id();
        AuditBatch batch = new AuditBatch(
                WebhookEvent.AUDIT_EXPORT,
                sink.name(),
                Instant.now().toString(),
                sink.cursorPosition(),
                to,
                entries.size(),
                entries);
        WebhookDelivery delivery =
                deliveryRepository.enqueue(sink.subscriberId(), WebhookEvent.AUDIT_EXPORT, serialize(batch));
        sinkRepository.updateCursor(sink.id(), to);
        return Optional.of(delivery);
    }

    /** Entries younger than the settling lag are not exported yet; see GW_0028's rationale. */
    private Instant cutoff() {
        return Instant.now().minus(properties.lag());
    }

    private String serialize(FetchLogRepository.AuditEntry entry) {
        try {
            return MAPPER.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit entry serialization failed", e);
        }
    }

    private String serialize(AuditBatch batch) {
        try {
            return MAPPER.writeValueAsString(batch);
        } catch (JsonProcessingException e) {
            log.error("audit export batch serialization failed for sink {}", batch.sink(), e);
            throw new IllegalStateException("audit export batch serialization failed", e);
        }
    }
}
