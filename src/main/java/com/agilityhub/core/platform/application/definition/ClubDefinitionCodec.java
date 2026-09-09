package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleDependencyValidator;
import com.agilityhub.core.platform.domain.HostNames;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class ClubDefinitionCodec {
    private final ObjectMapper mapper;
    private final JsonSchema schema;
    private final ModuleDependencyValidator modules;
    public ClubDefinitionCodec(ObjectMapper mapper, ModuleDependencyValidator modules) throws IOException {
        this.mapper = mapper; this.modules = modules;
        try (var input = new ClassPathResource("seeds/club-definition.schema.json").getInputStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        }
    }
    public ObjectNode read(Path path) {
        try (var input = Files.newInputStream(path)) {
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            return validate(mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(input)));
        } catch (IOException | org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("reason", "Unreadable or invalid YAML"));
        }
    }
    public ObjectNode validate(com.fasterxml.jackson.databind.JsonNode input) {
        var errors = schema.validate(input);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("fields",
                    errors.stream().map(error -> error.getInstanceLocation().toString() + ": " + error.getType()).sorted().toList()));
        }
        ObjectNode definition = ((ObjectNode) input).deepCopy();
        var club = definition.path("club");
        try {
            ZoneId.of(club.path("timeZone").asText()); Currency.getInstance(club.path("currency").asText());
            boolean localePresent = false;
            for (var locale : club.path("locales")) { localePresent |= locale.equals(club.path("defaultLocale")); }
            if (!localePresent) { throw new IllegalArgumentException(); }
            if (club.path("template").asBoolean() && !definition.path("domains").isEmpty()) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("field", "club"));
        }
        if (definition.has("preset")) {
            definition.set("modules", mapper.valueToTree(Module.defaults(Module.Preset.valueOf(definition.remove("preset").asText()))));
        }
        Set<Module> enabled = new HashSet<>();
        definition.path("modules").forEach(module -> enabled.add(Module.valueOf(module.asText())));
        modules.validate(enabled);
        definition.set("modules", mapper.valueToTree(enabled.stream().map(Enum::name).sorted().toList()));
        Set<String> hosts = new HashSet<>();
        for (var domain : definition.path("domains")) {
            String host = HostNames.normalize(domain.path("host").asText());
            if (host.isEmpty() || !hosts.add(host)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("field", "domains")); }
            if (host.matches("^(clubs|clubsadmin|id|core)\\..*")) { throw new ApiException(ErrorCode.HOST_RESERVED); }
            ((ObjectNode) domain).put("host", host);
            if (!domain.has("app")) { ((ObjectNode) domain).put("app", "clubs"); }
        }
        Set<String> emails = new HashSet<>();
        for (String section : java.util.List.of("admins", "accounts")) {
            for (var account : definition.path(section)) {
                String email = java.text.Normalizer.normalize(account.path("email").asText().trim()
                        .toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFC);
                if (!emails.add(email)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("field", section)); }
                ((ObjectNode) account).put("email", email);
            }
        }
        return definition;
    }
    public String write(ObjectNode definition) {
        var options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(true);
        return new Yaml(options).dump(mapper.convertValue(definition, java.util.LinkedHashMap.class));
    }
}
