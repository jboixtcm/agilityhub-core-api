package com.agilityhub.core.identity.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** HIBP range API: only the first five SHA-1 characters leave the process; no API key. */
@Component
public class CompromisedPasswords {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    @Autowired public CompromisedPasswords() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), URI.create("https://api.pwnedpasswords.com/range/"), Duration.ofSeconds(2));
    }
    public CompromisedPasswords(HttpClient client, URI endpoint, Duration timeout) {
        this.client = client; this.endpoint = endpoint; this.timeout = timeout;
    }
    public boolean contains(String password) {
        String hash;
        try { hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve(hash.substring(0, 5))).timeout(timeout)
                    .header("Add-Padding", "true").header("User-Agent", "AgilityHub-ID").GET().build();
            pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) { throw new IllegalStateException("HIBP unavailable"); }
            return response.body().lines().anyMatch(line -> {
                var parts = line.split(":", -1);
                return parts.length == 2 && hash.substring(5).equalsIgnoreCase(parts[0]) && parts[1].matches("[0-9]+")
                        && parts[1].chars().anyMatch(c -> c != '0');
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warn(); return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException unavailable) {
            warn(); return false;
        } finally { if (pending != null && !pending.isDone()) { pending.cancel(true); } }
    }
    private void warn() { LoggerFactory.getLogger(CompromisedPasswords.class).warn("Compromised password check unavailable; continuing per A2"); }
}
