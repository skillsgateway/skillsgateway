package dev.skillsgateway.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

/** RSA keys generated at test time, in the PEM forms GitHub hands out; no key is ever committed. */
public final class TestKeys {

    private TestKeys() {}

    public static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code BEGIN RSA PRIVATE KEY}: what GitHub issues for an App. */
    public static String pkcs1Pem(KeyPair key) {
        return pem("RSA PRIVATE KEY", pkcs1Of(key.getPrivate().getEncoded()));
    }

    /** {@code BEGIN PRIVATE KEY}. */
    public static String pkcs8Pem(KeyPair key) {
        return pem("PRIVATE KEY", key.getPrivate().getEncoded());
    }

    public static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /** The RSAPrivateKey inside a PKCS#8 PrivateKeyInfo: its last element, an OCTET STRING. */
    static byte[] pkcs1Of(byte[] info) {
        int[] at = {0};
        header(info, at); // PrivateKeyInfo SEQUENCE
        int version = header(info, at); // version INTEGER
        at[0] += version;
        int algorithm = header(info, at); // AlgorithmIdentifier SEQUENCE
        at[0] += algorithm;
        int length = header(info, at); // privateKey OCTET STRING
        return Arrays.copyOfRange(info, at[0], at[0] + length);
    }

    /** Reads one DER tag and length at {@code at}, leaving {@code at} on the contents. */
    private static int header(byte[] der, int[] at) {
        at[0]++;
        int first = der[at[0]++] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int length = 0;
        for (int i = 0; i < (first & 0x7f); i++) {
            length = (length << 8) | (der[at[0]++] & 0xff);
        }
        return length;
    }
}
