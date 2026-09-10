package com.agilityhub.core.identity.api;

import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TokenScopeIT extends IdentityIntegrationSupport {
    @ParameterizedTest
    @ValueSource(strings = {"clubs-app", "clubs-admin"})
    void T_01_06_INC06_passwordAndRefreshAlwaysIncludeEmptyScope(String client) throws Exception {
        var response = mvc.perform(post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "password").param("client_id", client)
                        .param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(""))
                .andExpect(jsonPath("$.access_token").isNotEmpty()).andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse();
        var token = readTokens(response);
        assertThat(token.has("refresh_token")).isFalse();
        refresh(refreshValue(token), HOST, client).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"clubs-app", "clubs-admin"})
    void T_01_06_INC06_nonEmptyScopesSurviveSerialization(String client) throws Exception {
        var response = mvc.perform(post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "password").param("client_id", client).param("scope", "openid profile offline_access")
                        .param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id_token").doesNotExist()).andReturn().getResponse();
        var token = readTokens(response);
        assertThat(Set.of(token.path("scope").asText().split(" "))).containsExactlyInAnyOrder("openid", "profile", "offline_access");
        var refreshed = readTokens(refresh(refreshValue(token), HOST, client).andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").doesNotExist()).andReturn().getResponse());
        assertThat(Set.of(refreshed.path("scope").asText().split(" "))).isEqualTo(Set.of("openid", "profile", "offline_access"));
    }
}
