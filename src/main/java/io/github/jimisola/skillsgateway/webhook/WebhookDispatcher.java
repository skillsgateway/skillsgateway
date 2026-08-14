package io.github.jimisola.skillsgateway.webhook;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.WebhookDelivery;
import io.github.jimisola.skillsgateway.persistence.WebhookDeliveryRepository;
import io.github.jimisola.skillsgateway.persistence.WebhookSubscriber;
import io.github.jimisola.skillsgateway.persistence.WebhookSubscriberRepository;
import io.github.reqstool.annotations.Requirements;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers queued webhooks out of band: claims due deliveries, POSTs the stored payload with its
 * HMAC signature, and reschedules failures with exponential backoff until the attempt budget is
 * spent (GW_0025). Claiming is an atomic conditional UPDATE, so a second instance of the poller
 * (or a future second replica) cannot take the same delivery.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriberRepository subscriberRepository;
    private final WebhookSigner signer;
    private final SkillsGatewayProperties.Webhooks properties;
    private final RestClient restClient;

    public WebhookDispatcher(
            WebhookDeliveryRepository deliveryRepository,
            WebhookSubscriberRepository subscriberRepository,
            WebhookSigner signer,
            SkillsGatewayProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.subscriberRepository = subscriberRepository;
        this.signer = signer;
        this.properties = properties.webhooks();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(this.properties.timeout())
                .build());
        factory.setReadTimeout(this.properties.timeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.webhooks.poll-interval:5s}",
            initialDelayString = "${skills-gateway.webhooks.poll-interval:5s}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("webhook dispatch pass failed", e);
        }
    }

    /** One dispatch pass over everything that is due; returns the number of attempts made. */
    @Requirements({"GW_0025"})
    public int dispatchDue() {
        List<Long> due = deliveryRepository.dueIds(properties.batchSize());
        int attempted = 0;
        for (long id : due) {
            // The lease keeps a claimed delivery invisible to a concurrent pass until it is either
            // completed below or the lease expires (crash recovery).
            Optional<WebhookDelivery> claimed =
                    deliveryRepository.claim(id, Instant.now().plus(leaseDuration()));
            if (claimed.isPresent()) {
                attempt(claimed.get());
                attempted++;
            }
        }
        return attempted;
    }

    private void attempt(WebhookDelivery delivery) {
        int attempts = delivery.attempts() + 1;
        Optional<WebhookSubscriber> subscriber = subscriberRepository.findById(delivery.subscriberId());
        if (subscriber.isEmpty()) {
            deliveryRepository.markFailed(delivery.id(), attempts, null, "subscriber no longer exists");
            return;
        }
        try {
            int status = post(subscriber.get(), delivery);
            if (status >= 200 && status < 300) {
                deliveryRepository.markDelivered(delivery.id(), attempts, status);
            } else {
                reschedule(delivery, attempts, status, "HTTP " + status);
            }
        } catch (RuntimeException e) {
            reschedule(delivery, attempts, null, e.toString());
        }
    }

    private int post(WebhookSubscriber subscriber, WebhookDelivery delivery) {
        return restClient
                .post()
                .uri(subscriber.url())
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSigner.EVENT_HEADER, delivery.event())
                .header(WebhookSigner.DELIVERY_HEADER, Long.toString(delivery.id()))
                .header(WebhookSigner.TIMESTAMP_HEADER, Instant.now().toString())
                .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(subscriber.secret(), delivery.payload()))
                .body(delivery.payload())
                .exchange((request, response) -> response.getStatusCode().value(), false);
    }

    /** Exponential backoff {@code base * 2^(attempts-1)}, capped, until the attempt budget is spent. */
    private void reschedule(WebhookDelivery delivery, int attempts, Integer status, String error) {
        if (attempts >= properties.maxAttempts()) {
            log.warn("webhook delivery {} failed after {} attempts: {}", delivery.id(), attempts, error);
            deliveryRepository.markFailed(delivery.id(), attempts, status, error);
            return;
        }
        deliveryRepository.markRetry(delivery.id(), attempts, Instant.now().plus(backoff(attempts)), status, error);
    }

    Duration backoff(int attempts) {
        Duration backoff = properties.baseBackoff().multipliedBy(1L << Math.min(attempts - 1, 32));
        return backoff.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : backoff;
    }

    /** How long a claimed delivery stays invisible; generous enough to cover the HTTP timeout. */
    private Duration leaseDuration() {
        return properties.timeout().plus(properties.timeout()).plusSeconds(5);
    }
}
