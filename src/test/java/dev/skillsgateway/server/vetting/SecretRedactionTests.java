package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/** The redactor masks what the secret-scan rules report and leaves everything else as written (GW_VETTING_0065). */
class SecretRedactionTests {

    @Test
    @SVCs({"SVC_GW_VETTING_0068"})
    void each_credential_shape_is_masked_and_its_surroundings_kept() {
        assertThat(SecretScanVetter.redact("gh --token ghp_" + "Ab3dEf6hIj9lMn2pQr5tUv8xYz1aBc4dEf7h now"))
                .isEqualTo("gh --token [redacted] now");
        assertThat(SecretScanVetter.redact("AWS_ACCESS_KEY_ID=" + "AKIA" + "QX7ZK2M4P9W3T6RV ./up.sh"))
                .isEqualTo("AWS_ACCESS_KEY_ID=[redacted] ./up.sh");
        assertThat(SecretScanVetter.redact("curl -H 'xoxb-" + "1234567890-abcdefghij' x"))
                .isEqualTo("curl -H '[redacted]' x");
        assertThat(SecretScanVetter.redact("export API_KEY=\"" + "q8Zr2Lw9Xv4Nt7Bk1Mc6Hy3Pd5Fj0Gs\" && run"))
                .isEqualTo("export API_KEY=\"[redacted]\" && run");
        assertThat(SecretScanVetter.redact("echo '-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nIBAAK\n"
                        + "-----END RSA PRIVATE KEY-----' > k"))
                .isEqualTo("echo '[redacted]' > k");
        assertThat(SecretScanVetter.redact("printf '-----BEGIN OPENSSH PRIVATE KEY-----\nb3Blbn"))
                .isEqualTo("printf '[redacted]");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0068"})
    void text_holding_no_credential_is_returned_unchanged() {
        String command =
                "${CLAUDE_PLUGIN_ROOT}/scripts/check.sh --token-file ~/.config/t password=aaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        assertThat(SecretScanVetter.redact(command)).isEqualTo(command);
        assertThat(SecretScanVetter.redact(null)).isNull();
    }
}
