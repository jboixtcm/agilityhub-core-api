package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.AuditCovers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlatformRolesIT extends IdentityIntegrationSupport {
    static final String PATH = "/api/v1/platform/accounts/";
    static final Set<String> ADMIN = Set.of("AGILITYHUB_ADMIN");
    @Autowired PlatformRoleService roles;
    @Autowired IdentityTransactions transactions;
    @Autowired GrantPlatformAdminCommand command;
    @BeforeEach void prepareRoles() {
        mongo.remove(new Query(), AuditEntry.class);
        accounts.platformRoles("account-a", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN));
        accounts.save(new Account("account-b", "target@example.test", "Example Target", "en", null, Set.of(),
                Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("account-a").claim("platformRoles", List.of("AGILITYHUB_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"));
    }
    @Test @AuditCovers(AuditAction.PLATFORM_ROLES_CHANGED)
    void T_17_09_globalReadReplaceAndRemovalAuditOnlyRolePathWithoutDomainEvent() throws Exception {
        long events = mongo.count(new Query(), DomainEventRecord.class);
        mvc.perform(get(PATH + "account-b/platform-roles").with(admin()).header("Host", "b.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platformRoles").isEmpty());
        for (int n = 0; n < 2; n++) {
            mvc.perform(put(PATH + "account-b/platform-roles").with(admin()).header("Host", HOST).contentType("application/json")
                    .content("{\"platformRoles\":[\"AGILITYHUB_ADMIN\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.platformRoles[0]").value("AGILITYHUB_ADMIN"));
        }
        var audits = mongo.findAll(AuditEntry.class);
        assertThat(audits).hasSize(1);
        var audit = audits.getFirst();
        assertThat(audit.action()).isEqualTo(AuditAction.PLATFORM_ROLES_CHANGED);
        assertThat(audit.entityType()).isEqualTo("Account"); assertThat(audit.entityId()).isEqualTo("account-b");
        assertThat(audit.clubId()).isNull(); assertThat(audit.actorAccountId()).isEqualTo("account-a");
        assertThat(audit.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("platformRoles");
            assertThat(change.before()).isEqualTo(List.of()); assertThat(change.after()).isEqualTo(List.of("AGILITYHUB_ADMIN"));
        });
        mvc.perform(put(PATH + "account-b/platform-roles").with(admin()).contentType("application/json").content("{\"platformRoles\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platformRoles").isEmpty());
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(2);
        assertThat(mongo.count(new Query(), DomainEventRecord.class)).isEqualTo(events);
    }
    @Test void T_17_09_forbiddenMissingInvalidAndLastAdminUseCatalogErrors() throws Exception {
        for (var method : List.of("GET", "PUT")) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method), PATH + "account-b/platform-roles")
                    .contentType("application/json").content("{\"platformRoles\":[]}");
            mvc.perform(request).andExpect(status().isUnauthorized());
            for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
                mvc.perform(request.with(jwt().jwt(token -> token.subject("account-a")).authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
            mvc.perform(request.with(admin()).with(jwt().jwt(token -> token.subject("account-b"))
                    .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(PATH + "absent/platform-roles").with(admin())).andExpect(status().isNotFound());
        mvc.perform(put(PATH + "absent/platform-roles").with(admin()).contentType("application/json").content("{\"platformRoles\":[]}"))
                .andExpect(status().isNotFound());
        for (String body : List.of("{}", "{\"platformRoles\":[\"ADMIN\"]}", "{\"platformRoles\":[null]}")) {
            mvc.perform(put(PATH + "account-b/platform-roles").with(admin()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(put(PATH + "account-a/platform-roles").with(admin()).contentType("application/json").content("{\"platformRoles\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        assertThat(mongo.findAll(AuditEntry.class)).isEmpty();
    }
    @Test void T_17_09_simultaneousSelfRemovalsKeepOneActiveAdmin() throws Exception {
        accounts.platformRoles("account-b", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN));
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<String>>of(() -> remove("account-a", gate), () -> remove("account-b", gate)));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("OK", "LAST_PLATFORM_ADMIN");
        }
        assertThat(accounts.activePlatformAdmins()).isEqualTo(1);
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(1);
    }
    String remove(String id, java.util.concurrent.CyclicBarrier gate) throws Exception {
        gate.await();
        try { transactions.run(() -> roles.replace(id, id, Set.of())); return "OK"; }
        catch (ApiException exception) { return exception.code().name(); }
    }
    @Test void T_17_09_roleAndAuditRollbackTogetherAndRemovedCallerLosesAccessImmediately() throws Exception {
        assertThatThrownBy(() -> transactions.run(() -> {
            roles.replace("account-a", "account-b", ADMIN);
            assertThat(mongo.findAll(AuditEntry.class)).hasSize(1);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(accounts.findById("account-b").orElseThrow().platformRoles()).isEmpty();
        assertThat(mongo.findAll(AuditEntry.class)).isEmpty();
        transactions.run(() -> roles.replace("account-a", "account-b", ADMIN));
        transactions.run(() -> roles.replace("account-b", "account-a", Set.of()));
        mvc.perform(get(PATH + "account-b/platform-roles").with(admin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put(PATH + "account-a/platform-roles").with(admin()).contentType("application/json")
                .content("{\"platformRoles\":[\"AGILITYHUB_ADMIN\"]}"))
                .andExpect(status().isForbidden());
    }
    @Test void T_17_09_cliBootstrapsExistingAccountIdempotentlyAndRejectsInvalidInput() {
        accounts.platformRoles("account-a", Set.of());
        assertThat(command.name()).isEqualTo("identity:grant-platform-admin");
        for (int n = 0; n < 2; n++) { command.run(new DefaultApplicationArguments(" TARGET@EXAMPLE.TEST ")); }
        assertThat(accounts.activePlatformAdmins()).isEqualTo(1);
        assertThat(mongo.findAll(AuditEntry.class)).singleElement().satisfies(audit -> {
            assertThat(audit.actorRole()).isEqualTo("SYSTEM"); assertThat(audit.clubId()).isNull();
        });
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments())).hasMessageContaining("Usage:");
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("absent@example.test"))).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> transactions.run(() -> roles.replace("account-b", "account-a", Set.of("ADMIN"))))
                .hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> transactions.run(() -> roles.replace("account-b", "account-a", null))).hasMessage("VALIDATION_ERROR");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().set("status", "BLOCKED"), Account.class);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("admin@example.test"))).hasMessage("ACCOUNT_BLOCKED");
    }
}
