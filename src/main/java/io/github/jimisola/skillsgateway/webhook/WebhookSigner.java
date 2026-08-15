package io.github.jimisola.skillsgateway.webhook;

import io.github.reqstool.annotations.Requirements;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 over the exact request body, in the GitHub-compatible {@code sha256=<hex>} form. */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** Header carrying the signature of the body. */
    public static final String SIGNATURE_HEADER = "X-Skills-Gateway-Signature";

    /** Header carrying the lifecycle event name. */
    public static final String EVENT_HEADER = "X-Skills-Gateway-Event";

    /** Header carrying the delivery id — the receiver's de-duplication key. */
    public static final String DELIVERY_HEADER = "X-Skills-Gateway-Delivery";

    /** Header carrying the attempt timestamp. */
    public static final String TIMESTAMP_HEADER = "X-Skills-Gateway-Timestamp";

    @Requirements({"GW_0024"})
    public String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
