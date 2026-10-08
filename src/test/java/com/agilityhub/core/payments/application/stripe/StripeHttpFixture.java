package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.payments.application.ProviderSecretVault;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual SDK HTTP errors, with no external requests or real credentials. */
public final class StripeHttpFixture implements AutoCloseable {
    private final HttpServer server;
    public final AtomicInteger requests = new AtomicInteger();
    public int status = 401;
    public StripeHttpFixture() throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/refunds", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Fixture authentication rejection\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 429) { exchange.getResponseHeaders().set("Stripe-Rate-Limited-Reason", "global-rate"); }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }
    public StripePaymentProvider provider(ClubPaymentProviders settings, ProviderSecretVault vault, SignupCheckoutRepository sessions) {
        return new StripePaymentProvider(settings, vault, new StripeCalls(), sessions) {
            @Override StripeClient newClient(String key) {
                return StripeClient.builder().setApiKey(key).setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
                        .setConnectTimeout(1000).setReadTimeout(3000).setMaxNetworkRetries(0).build();
            }
        };
    }
    @Override public void close() { server.stop(0); }
}
