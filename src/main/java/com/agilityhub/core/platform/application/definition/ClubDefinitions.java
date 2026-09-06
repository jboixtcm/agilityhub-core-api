package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.ClubAdminProvisioner;
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
    private final ClubAdminProvisioner admins;
    private final ClubConfigService configs;
    private final HostTenantResolver hosts;
    private final ObjectMapper mapper;
    public ClubDefinitions(ClubDefinitionCodec codec, ClubDefinitionWriter writer, ClubDefinitionMapper definitions,
                           ClubRepository clubs, ParameterRepository parameters, ClubAdminProvisioner admins,
                           ClubConfigService configs, HostTenantResolver hosts, ObjectMapper mapper) {
        this.codec = codec; this.writer = writer; this.definitions = definitions; this.clubs = clubs;
        this.parameters = parameters; this.admins = admins; this.configs = configs; this.hosts = hosts; this.mapper = mapper;
    }
    public ClubDefinitionWriter.Result apply(Path file, boolean dryRun) { return apply(codec.read(file), dryRun); }
    public ClubDefinitionWriter.Result apply(ObjectNode input, boolean dryRun) {
        var definition = codec.validate(input);
        String slug = definition.path("club").path("slug").asText();
        String id = clubs.findBySlug(slug).map(club -> club.id()).orElseGet(() -> UUID.randomUUID().toString());
        try (var scope = TenantContext.open(id)) {
            var result = dryRun ? writer.preview(definition) : writer.apply(definition);
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
        }
    }
    public ObjectNode export(String slug) {
        var club = clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        try (var scope = TenantContext.open(club.id())) {
            var definition = definitions.export(club);
            var overrides = definition.putObject("parameters");
            parameters.findAll().stream().filter(parameter -> parameter.scopeRef() == null)
                    .sorted(java.util.Comparator.comparing(parameter -> parameter.key()))
                    .forEach(parameter -> overrides.set(parameter.key(), mapper.valueToTree(parameter.value())));
            definition.set("admins", mapper.valueToTree(admins.list()));
            var catalogs = definition.putObject("catalogs");
            for (String part : java.util.List.of("levels", "rings", "instructors", "plans", "prices", "faq")) { catalogs.putArray(part); }
            definition.putArray("messageTemplates");
            return definition;
        }
    }
}
