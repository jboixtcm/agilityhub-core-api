package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.ClubAccountProvisioner;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ClubDefinitions {
    private final ClubDefinitionCodec codec;
    private final ClubDefinitionWriter writer;
    private final ClubDefinitionMapper definitions;
    private final ClubRepository clubs;
    private final ParameterRepository parameters;
    private final ClubAccountProvisioner accounts;
    private final ClubConfigService configs;
    private final HostTenantResolver hosts;
    private final com.agilityhub.core.platform.application.ClubCatalogProvisioner catalogs;
    private final ObjectMapper mapper;
    private final com.agilityhub.core.platform.application.ClubPageProvisioner pages;
    public ClubDefinitions(ClubDefinitionCodec codec, ClubDefinitionWriter writer, ClubDefinitionMapper definitions,
                           ClubRepository clubs, ParameterRepository parameters, ClubAccountProvisioner accounts,
                           ClubConfigService configs, HostTenantResolver hosts, ObjectMapper mapper, com.agilityhub.core.platform.application.ClubPageProvisioner pages, com.agilityhub.core.platform.application.ClubCatalogProvisioner catalogs) {
        this.codec = codec; this.writer = writer; this.definitions = definitions; this.clubs = clubs;
        this.parameters = parameters; this.accounts = accounts; this.configs = configs; this.hosts = hosts; this.mapper = mapper; this.pages = pages; this.catalogs = catalogs;
    }
    public ClubDefinitionWriter.Result apply(Path file, boolean dryRun) { return apply(codec.read(file), dryRun); }
    public ClubDefinitionWriter.Result apply(Path file, boolean dryRun, boolean allowSeedPasswords) {
        return apply(codec.read(file), dryRun, allowSeedPasswords, false);
    }
    public ClubDefinitionWriter.Result applyAccounts(Path file, boolean dryRun, boolean allowSeedPasswords) {
        return apply(codec.read(file), dryRun, allowSeedPasswords, true);
    }
    public ClubDefinitionWriter.Result apply(ObjectNode input, boolean dryRun) {
        return apply(input, dryRun, false, false);
    }
    private ClubDefinitionWriter.Result apply(ObjectNode input, boolean dryRun, boolean allowSeedPasswords, boolean accountsOnly) {
        var definition = codec.validate(input);
        String slug = definition.path("club").path("slug").asText();
        String id = clubs.findBySlug(slug).map(club -> club.id()).orElseGet(() -> UUID.randomUUID().toString());
        try (var scope = TenantContext.open(id)) {
            var result = dryRun ? writer.preview(definition, allowSeedPasswords, accountsOnly)
                    : writer.apply(definition, allowSeedPasswords, accountsOnly);
            if (!dryRun && result.changes() > 0) { configs.invalidate(id); hosts.invalidate(); }
            return result;
        } catch (DuplicateKeyException conflict) {
            // Check the committed state after transaction rollback; never expose raw Mongo error text.
            for (var domain : definition.path("domains")) {
                if (clubs.findByAnyHost(domain.path("host").asText()).filter(owner -> !owner.id().equals(id)).isPresent()) {
                    throw new ApiException(ErrorCode.HOST_ALREADY_USED);
                }
            }
            throw new ApiException(ErrorCode.SLUG_TAKEN);
        } finally { if (!dryRun) { configs.invalidate(id); } }
    }
    public ObjectNode export(String slug) {
        var club = clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        try (var scope = TenantContext.open(club.id())) {
            var definition = definitions.export(club);
            var overrides = definition.putObject("parameters");
            parameters.findAll().stream().filter(parameter -> parameter.scopeRef() == null)
                    .sorted(java.util.Comparator.comparing(parameter -> parameter.key()))
                    .forEach(parameter -> overrides.set(parameter.key(), mapper.valueToTree(parameter.value())));
            var exportedAccounts = definition.putArray("accounts");
            for (var account : accounts.list()) {
                ObjectNode entry = mapper.valueToTree(account);
                entry.remove("password");
                exportedAccounts.add(entry);
            }
            definition.set("catalogs", mapper.valueToTree(catalogs.export()));
            definition.putArray("messageTemplates");
            definition.set("pages", mapper.valueToTree(pages.list()));
            return definition;
        }
    }
}
