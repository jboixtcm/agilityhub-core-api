package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E7-T02 step 7 against a local push service: the request carries the RFC 8291 `aes128gcm` body — decrypted here with the
 * browser's private key through an independent implementation of the key schedule — the VAPID JWT of RFC 8292 (verified
 * with the product's public key) and the TTL; the service's answers map to `OK · GONE · RETRYABLE · FAILED` (R-11-07).
 */
class WebPushSenderTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private CompletableFuture<Captured> captured;
    private int responseStatus = 201;
    private long delay;
    private KeyPair vapid, browser;
    private byte[] auth;
    record Captured(String path, String encoding, String contentType, String ttl, String authorization, byte[] body) { }

    @BeforeEach void start() throws Exception {
        captured = new CompletableFuture<>();
        vapid = p256(); browser = p256(); auth = new byte[16]; new SecureRandom().nextBytes(auth);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/push/", exchange -> {
            var headers = exchange.getRequestHeaders();
            captured.complete(new Captured(exchange.getRequestURI().getPath(), headers.getFirst("Content-Encoding"), headers.getFirst("Content-Type"),
                    headers.getFirst("TTL"), headers.getFirst("Authorization"), exchange.getRequestBody().readAllBytes()));
            try { if (delay > 0) { Thread.sleep(delay); } } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(responseStatus, -1); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private static KeyPair p256() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }
    private static byte[] point(ECPublicKey key) {
        byte[] out = new byte[65]; out[0] = 4;
        copy(key.getW().getAffineX(), out, 1); copy(key.getW().getAffineY(), out, 33); return out;
    }
    private static byte[] scalar(ECPrivateKey key) { byte[] out = new byte[32]; copy(key.getS(), out, 0); return out; }
    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray(); int start = Math.max(0, bytes.length - 32), length = Math.min(32, bytes.length);
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }
    private WebPushSender sender(Duration timeout) {
        return new WebPushSender(HttpClient.newHttpClient(), mapper, Clock.fixed(NOW, ZoneOffset.UTC), timeout, URL.encodeToString(point((ECPublicKey) vapid.getPublic())),
                URL.encodeToString(scalar((ECPrivateKey) vapid.getPrivate())), "mailto:product@example.test", () -> 86_400);
    }
    private PushSubscription subscription(String endpoint) {
        return new PushSubscription("subscription-a", "club-a", "account-a", endpoint, null,
                new PushSubscription.Keys(URL.encodeToString(point((ECPublicKey) browser.getPublic())), URL.encodeToString(auth)), "iPhone · Safari", "UA",
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, NOW, "account-a", NOW, "account-a");
    }
    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/push/device-a"; }
    private static PushPayload payload() { return new PushPayload("notification-a", "Classe anul·lada pel club", "Dimecres 12 · 18:50", "x", "/notificacions", "N-08a"); }

    @Test void T_11_10_encryptedPayloadVapidJwtAndTtl() throws Exception {
        assertThat(sender(Duration.ofSeconds(2)).send(subscription(endpoint()), payload())).isEqualTo(PushResult.ok());
        var request = captured.get();
        assertThat(request.path()).isEqualTo("/push/device-a"); assertThat(request.encoding()).isEqualTo("aes128gcm");
        assertThat(request.contentType()).isEqualTo("application/octet-stream"); assertThat(request.ttl()).isEqualTo("86400");
        // RFC 8291: the browser decrypts the body with its own private key and auth secret.
        var json = mapper.readTree(decrypt(request.body()));
        assertThat(json.path("notificationId").asText()).isEqualTo("notification-a"); assertThat(json.path("title").asText()).isEqualTo("Classe anul·lada pel club");
        assertThat(json.path("body").asText()).isEqualTo("Dimecres 12 · 18:50"); assertThat(json.path("icon").asText()).isEqualTo("x");
        assertThat(json.path("url").asText()).isEqualTo("/notificacions"); assertThat(json.path("tag").asText()).isEqualTo("N-08a");
        // RFC 8292: «vapid t=<JWT>, k=<public key>», ES256 over the endpoint's origin, 12 h, the product's subject.
        assertThat(request.authorization()).startsWith("vapid t=").endsWith(", k=" + URL.encodeToString(point((ECPublicKey) vapid.getPublic())));
        String jwt = request.authorization().substring("vapid t=".length(), request.authorization().indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(mapper.readTree(Base64.getUrlDecoder().decode(parts[0])).path("alg").asText()).isEqualTo("ES256");
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("aud").asText()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plus(Duration.ofHours(12)).getEpochSecond());
        assertThat(claims.path("sub").asText()).isEqualTo("mailto:product@example.test");
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format"); verifier.initVerify(vapid.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(sender(Duration.ofSeconds(2)).publicKey()).isEqualTo(URL.encodeToString(point((ECPublicKey) vapid.getPublic())));
    }

    @ParameterizedTest @CsvSource({"200,OK", "202,OK", "404,GONE", "410,GONE", "429,RETRYABLE", "500,RETRYABLE", "503,RETRYABLE", "400,FAILED", "413,FAILED"})
    void T_11_10_pushServiceAnswers(int status, PushResult.Status expected) {
        responseStatus = status;
        var result = sender(Duration.ofSeconds(2)).send(subscription(endpoint()), payload());
        assertThat(result.status()).isEqualTo(expected);
        if (expected != PushResult.Status.OK) { assertThat(result.error()).isEqualTo("Push HTTP " + status); }
    }

    @Test void T_11_10_timeoutTransportInterruptAndBrokenKeys() throws Exception {
        delay = 600;
        assertThat(sender(Duration.ofMillis(100)).send(subscription(endpoint()), payload())).isEqualTo(PushResult.of(PushResult.Status.RETRYABLE, "Push timeout"));
        var client = mock(HttpClient.class);
        var mocked = new WebPushSender(client, mapper, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), URL.encodeToString(point((ECPublicKey) vapid.getPublic())),
                URL.encodeToString(scalar((ECPrivateKey) vapid.getPrivate())), "mailto:product@example.test", () -> -5);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("endpoint"));
        assertThat(mocked.send(subscription("https://push.example.test:8443/a"), payload())).isEqualTo(PushResult.of(PushResult.Status.RETRYABLE, "Push transport failure"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try { assertThat(mocked.send(subscription("https://push.example.test/a"), payload()).status()).isEqualTo(PushResult.Status.RETRYABLE); assertThat(Thread.interrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        // A subscription whose browser key is not a P-256 point is a final failure of that delivery.
        var broken = new PushSubscription("subscription-b", "club-a", "account-a", endpoint(), null, new PushSubscription.Keys(URL.encodeToString(new byte[10]), URL.encodeToString(auth)),
                null, null, PushSubscription.Status.ACTIVE, 0, null, null, 0L, NOW, "account-a", NOW, "account-a");
        assertThat(sender(Duration.ofSeconds(2)).send(broken, payload())).isEqualTo(PushResult.of(PushResult.Status.FAILED, "Push subscription keys invalid"));
        // Keys that are not a base64url P-256 pair never start the sender (staging/prod fail fast).
        assertThatThrownBy(() -> new WebPushSender(HttpClient.newHttpClient(), mapper, Clock.systemUTC(), Duration.ofSeconds(1), "not-a-key", "x", "mailto:a@example.test", () -> 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WebPushSender.privateKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushSender.decode(null)).isInstanceOf(IllegalArgumentException.class);
        // A padded standard-base64 key is accepted too.
        assertThat(WebPushSender.decode(Base64.getEncoder().encodeToString(new byte[] {(byte) 0xfb, (byte) 0xff}))).containsExactly((byte) 0xfb, (byte) 0xff);
        // DER signatures with a long-form length and short integers still become 64 bytes.
        assertThat(WebPushSender.joseSignature(new byte[] {0x30, (byte) 0x81, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x07})).hasSize(64).endsWith((byte) 0x07);
    }

    @Test void T_11_10_theTestDoubleScriptsAnswersAndRemembersThePushes() {
        var fake = new FakePushSender("  ");
        assertThat(fake.publicKey()).isNull(); assertThat(new FakePushSender("public-key").publicKey()).isEqualTo("public-key");
        fake.answer(PushResult.of(PushResult.Status.GONE, "Push HTTP 410"));
        assertThat(fake.send(subscription(endpoint()), payload()).status()).isEqualTo(PushResult.Status.GONE);
        assertThat(fake.send(subscription(endpoint()), payload())).isEqualTo(PushResult.ok());
        assertThat(fake.sent()).extracting(FakePushSender.Sent::subscriptionId).containsExactly("subscription-a");
        assertThat(payload().toString()).doesNotContain("Classe").contains("notification-a");
        assertThat(subscription(endpoint()).toString()).doesNotContain("127.0.0.1"); assertThat(subscription(endpoint()).keys().toString()).isEqualTo("Keys[redacted]");
        fake.clear(); assertThat(fake.sent()).isEmpty();
    }

    /** RFC 8291 §3.3/§3.4 and RFC 8188 §2, written independently of the sender: the browser's side. */
    private byte[] decrypt(byte[] body) throws Exception {
        var buffer = ByteBuffer.wrap(body);
        byte[] salt = new byte[16]; buffer.get(salt);
        int recordSize = buffer.getInt(); assertThat(recordSize).isEqualTo(4096);
        byte[] keyId = new byte[buffer.get() & 0xff]; buffer.get(keyId);
        byte[] cipherText = new byte[buffer.remaining()]; buffer.get(cipherText);
        var asPublic = WebPushSender.publicKey(keyId);
        var agreement = KeyAgreement.getInstance("ECDH"); agreement.init(browser.getPrivate()); agreement.doPhase(asPublic, true);
        byte[] ecdh = agreement.generateSecret();
        byte[] uaPublic = point((ECPublicKey) browser.getPublic());
        var info = new ByteArrayOutputStream(); info.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII)); info.writeBytes(uaPublic); info.writeBytes(keyId);
        byte[] ikm = hkdf(auth, ecdh, info.toByteArray(), 32);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(cipherText);
        assertThat(plain[plain.length - 1]).as("last-record delimiter").isEqualTo((byte) 2);
        return Arrays.copyOf(plain, plain.length - 1);
    }
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256")); mac.update(info); mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }
}
