package com.agilityhub.core.shared.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** E3-T09 step 2: the peer 127.0.0.1 is not a trusted proxy: a forged `X-Forwarded-For` is ignored and the key is the peer. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true","server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.254"})
class UntrustedPeerClientIpIT extends ForwardedClientSupport {
    @Test void R_04_20_forwardedHeaderOfAnUntrustedPeerIsIgnored() throws Exception {
        for(int i=0;i<10;i++) assertThat(post("/api/v1/signup/identity-checks","198.51.100."+(71+i),identityCheck(),null).status()).isEqualTo(200);
        assertThat(post("/api/v1/signup/identity-checks","198.51.100.99",identityCheck(),null).status()).as("one bucket: the peer").isEqualTo(429);
    }
    @Test void R_04_17_consentIpHashIsThePeerWhenItIsNotTrusted() throws Exception {
        var reply=post("/api/v1/signup","198.51.100.64",signup(),UUID.randomUUID().toString());
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        String member=mapper.readTree(reply.body().substring(reply.body().indexOf('{'),reply.body().lastIndexOf('}')+1)).path("memberId").asText();
        assertThat(consentHashes(member)).isNotEmpty().allMatch(capabilities.fingerprint("127.0.0.1")::equals);
    }
}
