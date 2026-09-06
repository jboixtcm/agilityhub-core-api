package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import({RequiresModuleIT.Config.class, RequiresModuleIT.MethodProbe.class, RequiresModuleIT.ClassProbe.class})
class RequiresModuleIT extends AbstractIntegrationTest {
    private static final String GUARD_PATH = "/api/v1/_test/module-guard";
    private static final String CLASS_PATH = "/api/v1/_test/module-class";
    private static final String OPEN_PATH = "/api/v1/_test/module-open";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ModuleGuard guard;

    @BeforeEach
    void prepare() {
        TenantContext.clear();
        mongo.remove(new Query(), Club.class);
        mongo.remove(new Query(), Parameter.class);
        configs.invalidate("club-a");
        configs.invalidate("club-b");
        hosts.invalidate();
        clubs.save(PlatformFixtures.club("club-a", "app.example.test"));
        clubs.save(PlatformFixtures.club("club-b", "b.example.test"));
        modules("club-a", Set.of(Module.FREE_TRAINING));
        modules("club-b", Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void T_02_09_moduleGuardUsesEachTenantsConfigForEveryAllowedRole(String role) throws Exception {
        mvc.perform(get(GUARD_PATH).header("Host", "app.example.test")
                        .with(jwt().jwt(token -> token.claim("clubId", "club-a")).authorities(() -> "ROLE_" + role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clubId").value("club-a"));
        mvc.perform(get(GUARD_PATH).header("Host", "b.example.test")
                        .with(jwt().jwt(token -> token.claim("clubId", "club-b")).authorities(() -> "ROLE_" + role)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(ErrorCode.MODULE_DISABLED.name()))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.length()").value(4));
        modules("club-a", Set.of());
        mvc.perform(get(GUARD_PATH).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                        .authorities(() -> "ROLE_" + role)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(ErrorCode.MODULE_DISABLED.name()));
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    void T_02_09_classAndMethodRequirementsAreCumulative() throws Exception {
        expectGet(CLASS_PATH, 200);
        expectGet(CLASS_PATH + "/billing", 404);
        modules("club-a", Set.of(Module.BILLING));
        expectGet(CLASS_PATH, 404);
        expectGet(CLASS_PATH + "/billing", 404);
        modules("club-a", Set.of(Module.FREE_TRAINING, Module.BILLING));
        expectGet(CLASS_PATH, 200);
        expectGet(CLASS_PATH + "/billing", 200);
    }

    @Test
    void T_02_09_disabledModuleWinsBeforeBodyDecodingAndValidation() throws Exception {
        for (String invalid : List.of("{\"name\":\"\"}", "{broken")) {
            modules("club-a", Set.of());
            mvc.perform(post(GUARD_PATH).with(csrf()).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                            .authorities(() -> "ROLE_MEMBER")).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(ErrorCode.MODULE_DISABLED.name()));
            modules("club-a", Set.of(Module.FREE_TRAINING));
            mvc.perform(post(GUARD_PATH).with(csrf()).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                            .authorities(() -> "ROLE_MEMBER")).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
        }
        mvc.perform(post(GUARD_PATH).with(csrf()).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                        .authorities(() -> "ROLE_MEMBER")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Example"));
    }

    @Test
    void T_02_09_testEndpointsPreserveTenantAndRoleChecks() throws Exception {
        for (String path : List.of(GUARD_PATH, CLASS_PATH, CLASS_PATH + "/billing", OPEN_PATH)) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                            .authorities(() -> "ROLE_GUEST"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().authorities(() -> "ROLE_MEMBER")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(ErrorCode.NO_MEMBERSHIP.name()));
            mvc.perform(get(path).header("Host", "b.example.test")
                            .with(jwt().jwt(token -> token.claim("clubId", "club-a")).authorities(() -> "ROLE_MEMBER")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(ErrorCode.TENANT_MISMATCH.name()));
        }
        mvc.perform(post(GUARD_PATH).with(csrf()).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                        .authorities(() -> "ROLE_GUEST"))).andExpect(status().isForbidden());
        mvc.perform(post(GUARD_PATH).with(csrf()).header("Host", "b.example.test")
                        .with(jwt().jwt(token -> token.claim("clubId", "club-a")).authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(ErrorCode.TENANT_MISMATCH.name()));
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    void T_02_09_unguardedEndpointsKeepWorkingWithAllModulesDisabled() throws Exception {
        modules("club-a", Set.of());
        expectGet(OPEN_PATH, 200);
        mvc.perform(get("/api/v1/branding").header("Host", "app.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modules").isEmpty());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void T_02_09_serviceAndSchedulerGuardSupportsEveryModuleAndRejectsCrossTenantCalls() {
        modules("club-a", EnumSet.allOf(Module.class));
        for (Module module : Module.values()) {
            assertThatCode(() -> guard.require("club-a", module)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.require("club-b", module))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DISABLED));
            assertThat(TenantContext.current()).isNull();
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThatThrownBy(() -> guard.require("club-a", Module.FREE_TRAINING))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
            assertThat(TenantContext.require()).isEqualTo("club-b");
        }
        assertThat(TenantContext.current()).isNull();
    }

    private void expectGet(String path, int expected) throws Exception {
        var result = mvc.perform(get(path).with(jwt().jwt(token -> token.claim("clubId", "club-a"))
                .authorities(() -> "ROLE_MEMBER"))).andExpect(status().is(expected));
        if (expected == 404) {
            result.andExpect(jsonPath("$.code").value(ErrorCode.MODULE_DISABLED.name()));
        }
    }

    private void modules(String clubId, Set<Module> enabled) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId)),
                new Update().set("modules", enabled.stream().map(Enum::name).toList()), Club.class);
        configs.invalidate(clubId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Order(0)
        SecurityFilterChain moduleProbes(HttpSecurity http) throws Exception {
            return http.securityMatcher(GUARD_PATH, CLASS_PATH, CLASS_PATH + "/billing", OPEN_PATH)
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"))
                    .build();
        }
    }

    @Profile("test")
    @RestController
    static class MethodProbe {
        @GetMapping(GUARD_PATH)
        @RequiresModule(Module.FREE_TRAINING)
        Map<String, String> guarded() { return Map.of("clubId", TenantContext.require()); }

        @PostMapping(GUARD_PATH)
        @RequiresModule(Module.FREE_TRAINING)
        Input validated(@Valid @RequestBody Input input) { return input; }

        @GetMapping(OPEN_PATH)
        Map<String, String> unguarded() { return Map.of("clubId", TenantContext.require()); }
    }

    @Profile("test")
    @RestController
    @RequiresModule(Module.FREE_TRAINING)
    static class ClassProbe {
        @GetMapping(CLASS_PATH)
        Map<String, String> guarded() { return Map.of("clubId", TenantContext.require()); }

        @GetMapping(CLASS_PATH + "/billing")
        @RequiresModule(Module.BILLING)
        Map<String, String> twoModules() { return Map.of("clubId", TenantContext.require()); }
    }

    record Input(@NotBlank String name) { }
}
