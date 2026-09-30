package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two guards the configuration records owe their siblings: a count at zero or below falls back to
 * its default instead of reaching code that divides, clamps or loops by it, and a record that
 * carries a secret keeps it out of the generated {@code toString}.
 */
class PropertyRecordGuardTests {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void counts_at_zero_or_below_take_their_defaults(int value) {
        var export = new SkillsGatewayProperties.AuditExport(null, null, null, value, value, value);
        assertThat(export.batchSize()).isEqualTo(500);
        assertThat(export.defaultPageSize()).isEqualTo(1000);
        assertThat(export.maxPageSize()).isEqualTo(10000);

        var webhooks = new SkillsGatewayProperties.Webhooks(null, null, null, null, value, null, value);
        assertThat(webhooks.maxAttempts()).isEqualTo(5);
        assertThat(webhooks.batchSize()).isEqualTo(50);

        var retention = new SkillsGatewayProperties.Retention(null, null, null, value, null, null, null, null);
        assertThat(retention.batchSize()).isEqualTo(200);
    }

    @Test
    void a_positive_count_is_kept() {
        assertThat(new SkillsGatewayProperties.AuditExport(null, null, null, 1, 2, 3))
                .extracting("batchSize", "defaultPageSize", "maxPageSize")
                .containsExactly(1, 2, 3);
        assertThat(new SkillsGatewayProperties.Webhooks(null, null, null, null, 1, null, 2))
                .extracting("maxAttempts", "batchSize")
                .containsExactly(1, 2);
        assertThat(new SkillsGatewayProperties.Retention(null, null, null, 1, null, null, null, null).batchSize())
                .isEqualTo(1);
    }

    @Test
    void records_carrying_a_secret_do_not_print_it() {
        String secret = "s3cr3t-value-never-printed";

        var credentials = new SkillsGatewayProperties.Credentials(
                SkillsGatewayProperties.Credentials.Mode.STATIC, "AKIAEXAMPLE", secret, null, null);
        assertThat(credentials.toString()).doesNotContain(secret).contains("AKIAEXAMPLE");

        var webhook = new SkillsGatewayProperties.DeclaredWebhook(
                "ci", "https://ci.example.com/hook", List.of("snapshot.approved"), secret);
        assertThat(webhook.toString()).doesNotContain(secret).contains("ci.example.com");

        var sink = new SkillsGatewayProperties.DeclaredAuditSink(
                "siem", "https://siem.example.com/ingest", secret, 42L, 100);
        assertThat(sink.toString()).doesNotContain(secret).contains("siem.example.com");
    }
}
