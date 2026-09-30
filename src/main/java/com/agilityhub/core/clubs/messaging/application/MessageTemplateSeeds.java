package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.ClubTemplateProvisioner;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * `club:apply`'s `messageTemplates` section (E7-T03 step 3, {@link ClubTemplateProvisioner}): the listed codes get their
 * product seed ({@link MessageTemplateSeed}) in the club's languages; a code already seeded — maybe edited at D9 — is kept.
 */
@Service
public class MessageTemplateSeeds implements ClubTemplateProvisioner {
    private final MessageTemplateRepository templates; private final TemplateProvider provider;

    public MessageTemplateSeeds(MessageTemplateRepository templates, TemplateProvider provider) { this.templates = templates; this.provider = provider; }

    @Override public List<String> plan(JsonNode messageTemplates) {
        var eligible = MessageTemplateSeed.eligible().stream().map(spec -> spec.code()).toList();
        var codes = new LinkedHashSet<String>();
        if (messageTemplates != null) {
            for (var entry : messageTemplates) {
                String code = entry.path("code").asText(null);
                if (code == null || !eligible.contains(code)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "messageTemplates.code")); }
                if (!codes.add(code)) { throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "messageTemplates.code")); }
            }
        }
        var present = new HashSet<String>();
        templates.findAll().forEach(template -> { if (template.code() != null) { present.add(template.code()); } });
        return codes.stream().filter(code -> !present.contains(code)).toList();
    }

    @Override public void provision(List<String> codes, List<String> locales, String defaultLocale) {
        for (String code : codes) { templates.insert(provider.seed(NotificationCatalog.byCode(code).orElseThrow(), locales, defaultLocale)); }
    }

    @Override public List<Map<String, Object>> export() {
        var order = NotificationCatalog.codes();
        var entries = new ArrayList<Map<String, Object>>();
        templates.findAll().stream().filter(template -> template.kind() == TemplateKind.CATALOG && order.contains(template.code()))
                .map(template -> template.code()).sorted(java.util.Comparator.comparingInt(order::indexOf))
                .forEach(code -> entries.add(Map.of("code", code)));
        return entries;
    }
}
