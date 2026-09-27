package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push with the JDK's own cryptography: the payload is encrypted with `aes128gcm` (RFC 8291 over RFC 8188, one record)
 * for the subscription's `p256dh`/`auth` keys, and the request carries a VAPID JWT (RFC 8292, ES256, 12 h) signed with the
 * product key pair of `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` (base64url: the uncompressed P-256 point and the 32-byte
 * scalar) and `VAPID_SUBJECT` (`mailto:` or `https:`). TTL = `messaging.push.ttlMinutes`. 201/202 → `OK`; 404/410 →
 * `GONE`; 429, 5xx, a timeout or a transport failure → `RETRYABLE`; anything else → `FAILED`. No endpoint or key is logged.
 */
public final class WebPushSender implements PushSender {
    static final String ENCODING = "aes128gcm";
    private static final int RECORD_SIZE = 4096;
    private final HttpClient client; private final ObjectMapper mapper; private final Clock clock; private final Duration timeout;
    private final String publicKey, subject; private final ECPublicKey vapidPublic; private final ECPrivateKey vapidPrivate;
    private final java.util.function.IntSupplier ttlSeconds;
    private final SecureRandom random = new SecureRandom();

    /** @param ttlSeconds the TTL of each push, read per send (`messaging.push.ttlMinutes` of the club) */
    public WebPushSender(HttpClient client, ObjectMapper mapper, Clock clock, Duration timeout, String publicKey, String privateKey, String subject,
            java.util.function.IntSupplier ttlSeconds) {
        this.client = client; this.mapper = mapper; this.clock = clock; this.timeout = timeout; this.publicKey = publicKey; this.subject = subject;
        this.ttlSeconds = ttlSeconds;
        try {
            this.vapidPublic = publicKey(decode(publicKey));
            this.vapidPrivate = privateKey(decode(privateKey));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not a base64url P-256 key pair");
        }
    }

    @Override public String publicKey() { return publicKey; }

    @Override public PushResult send(PushSubscription subscription, PushPayload payload) {
        try {
            var json = new LinkedHashMap<String, Object>();
            json.put("notificationId", payload.notificationId()); json.put("title", payload.title()); json.put("body", payload.body());
            json.put("icon", payload.icon()); json.put("url", payload.url()); json.put("tag", payload.tag());
            byte[] body = encrypt(mapper.writeValueAsBytes(json), decode(subscription.keys().p256dh()), decode(subscription.keys().auth()));
            var endpoint = URI.create(subscription.endpoint());
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Encoding", ENCODING).header("Content-Type", "application/octet-stream")
                    .header("TTL", Integer.toString(Math.max(0, ttlSeconds.getAsInt())))
                    .header("Authorization", "vapid t=" + jwt(endpoint) + ", k=" + publicKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 201 || status == 202 || status == 200) { return PushResult.ok(); }
            if (status == 404 || status == 410) { return PushResult.of(PushResult.Status.GONE, "Push HTTP " + status); }
            if (status == 429 || status >= 500) { return PushResult.of(PushResult.Status.RETRYABLE, "Push HTTP " + status); }
            return PushResult.of(PushResult.Status.FAILED, "Push HTTP " + status);
        } catch (HttpTimeoutException timedOut) {
            return PushResult.of(PushResult.Status.RETRYABLE, "Push timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PushResult.of(PushResult.Status.RETRYABLE, "Push interrupted");
        } catch (IOException transport) {
            return PushResult.of(PushResult.Status.RETRYABLE, "Push transport failure");
        } catch (GeneralSecurityException | IllegalArgumentException invalidKeys) {
            return PushResult.of(PushResult.Status.FAILED, "Push subscription keys invalid");
        }
    }

    /** RFC 8291 §3.4: `salt(16) · rs(4) · idlen(1) · keyid = as_public(65) · AES-128-GCM(plaintext · 0x02)`. */
    byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair ephemeral = generator.generateKeyPair();
        byte[] asPublic = uncompressed((ECPublicKey) ephemeral.getPublic());
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate()); agreement.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();
        byte[] salt = new byte[16]; random.nextBytes(salt);
        byte[][] keys = keys(ecdhSecret, authSecret, uaPublic, asPublic, salt);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
        byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1); padded[plaintext.length] = 0x02;
        byte[] cipherText = cipher.doFinal(padded);
        var out = new ByteArrayOutputStream();
        out.writeBytes(salt); out.writeBytes(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array()); out.write(asPublic.length); out.writeBytes(asPublic);
        out.writeBytes(cipherText);
        return out.toByteArray();
    }
    /** RFC 8291 §3.3–3.4 key schedule: `{CEK(16), NONCE(12)}`. */
    static byte[][] keys(byte[] ecdhSecret, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt) throws GeneralSecurityException {
        byte[] prkKey = hmac(authSecret, ecdhSecret);
        var keyInfo = new ByteArrayOutputStream();
        keyInfo.writeBytes("WebPush: info".getBytes(StandardCharsets.US_ASCII)); keyInfo.write(0); keyInfo.writeBytes(uaPublic); keyInfo.writeBytes(asPublic);
        byte[] ikm = expand(prkKey, keyInfo.toByteArray(), 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        return new byte[][] {cek, nonce};
    }
    /** RFC 8292 VAPID JWT for the endpoint's origin, 12 hours. */
    String jwt(URI endpoint) throws GeneralSecurityException, IOException {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        var claims = new LinkedHashMap<String, Object>();
        claims.put("aud", audience); claims.put("exp", clock.instant().plus(Duration.ofHours(12)).getEpochSecond()); claims.put("sub", subject);
        String signingInput = enc.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "." + enc.encodeToString(mapper.writeValueAsBytes(claims));
        var signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(vapidPrivate); signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + enc.encodeToString(joseSignature(signer.sign()));
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data);
    }
    /** HKDF-Expand with a single block (length ≤ 32). */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] input = Arrays.copyOf(info, info.length + 1); input[info.length] = 0x01;
        return Arrays.copyOf(hmac(prk, input), length);
    }
    static ECParameterSpec p256() throws GeneralSecurityException {
        var parameters = AlgorithmParameters.getInstance("EC"); parameters.init(new ECGenParameterSpec("secp256r1"));
        try { return parameters.getParameterSpec(ECParameterSpec.class); }
        catch (java.security.spec.InvalidParameterSpecException impossible) { throw new GeneralSecurityException(impossible); }
    }
    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) { throw new IllegalArgumentException("Not an uncompressed P-256 point"); }
        var point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)), new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, p256()));
    }
    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != 32) { throw new IllegalArgumentException("Not a P-256 private key"); }
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), p256()));
    }
    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65]; out[0] = 0x04;
        copy(key.getW().getAffineX(), out, 1); copy(key.getW().getAffineY(), out, 33);
        return out;
    }
    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32), length = Math.min(32, bytes.length);
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }
    /** DER `SEQUENCE {r, s}` → the 64-byte `r · s` of JWS ES256. */
    static byte[] joseSignature(byte[] der) {
        int offset = 2 + (der[1] == (byte) 0x81 ? 1 : 0);
        int rLength = der[offset + 1]; byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sOffset = offset + 2 + rLength; int sLength = der[sOffset + 1]; byte[] s = Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLength);
        byte[] out = new byte[64];
        copy(new BigInteger(1, r), out, 0); copy(new BigInteger(1, s), out, 32);
        return out;
    }
    static byte[] decode(String base64url) {
        if (base64url == null) { throw new IllegalArgumentException("missing key"); }
        return Base64.getUrlDecoder().decode(base64url.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    }
}
