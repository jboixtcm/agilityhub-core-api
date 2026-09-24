package com.agilityhub.core.shared.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** E3-T09 step 2: the peer 127.0.0.1 is the trusted proxy (like Caddy with `TRUSTED_PROXY_PATTERN`): the key is the client. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true","server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1"})
class TrustedProxyClientIpIT extends ForwardedClientSupport {
    @Test void R_04_20_forwardedClientIsTheLimiterKeyBehindATrustedProxy() throws Exception {
        for(int i=0;i<10;i++) assertThat(post("/api/v1/signup/identity-checks","198.51.100.61",identityCheck(),null).status()).isEqualTo(200);
        var limited=post("/api/v1/signup/identity-checks","198.51.100.61",identityCheck(),null);
        assertThat(limited.status()).isEqualTo(429);assertThat(limited.body()).contains("RATE_LIMITED");
        // Another client behind the same proxy has its own bucket.
        assertThat(post("/api/v1/signup/identity-checks","198.51.100.62",identityCheck(),null).status()).isEqualTo(200);
    }
    @Test void R_04_17_consentIpHashIsTheForwardedClient() throws Exception {
        var reply=post("/api/v1/signup","198.51.100.63",signup(),UUID.randomUUID().toString());
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        String member=mapper.readTree(reply.body().substring(reply.body().indexOf('{'),reply.body().lastIndexOf('}')+1)).path("memberId").asText();
        assertThat(consentHashes(member)).isNotEmpty().allMatch(capabilities.fingerprint("198.51.100.63")::equals);
    }
}
