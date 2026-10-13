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

    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
    @Override public List<Map<String,Object>> cloneSource() {
        return templates.findAll().stream().map(t->mapper.convertValue(t,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})).toList();
    }
    @Override public void cloneInto(List<Map<String,Object>> source,String locale) {
        if(!templates.findAll().isEmpty()) throw new ApiException(ErrorCode.CATALOG_NOT_EMPTY,Map.of("part","MESSAGE_TEMPLATES"));
        for(var row:source) {
            var old=mapper.convertValue(row,com.agilityhub.core.clubs.messaging.persistence.MessageTemplate.class);
            templates.insert(new com.agilityhub.core.clubs.messaging.persistence.MessageTemplate(java.util.UUID.randomUUID().toString(),
                    com.agilityhub.core.shared.application.TenantContext.require(),old.code(),old.kind(),old.category(),
                    old.title().withDefaultLocale(locale),old.body().withDefaultLocale(locale),old.smsBody()==null?null:old.smsBody().withDefaultLocale(locale),
                    old.icon(),old.color(),old.matrix(),old.enabled(),old.mandatory(),old.customized(),old.status(),null,
                    clock.instant(),null,clock.instant(),null));
        }
    }

    @Override public boolean complete(String locale) {
        var stored=templates.findAll();
        return MessageTemplateSeed.eligible().stream().allMatch(spec->stored.stream().anyMatch(t->spec.code().equals(t.code())
                && t.title().values().containsKey(locale) && t.body().values().containsKey(locale)));
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
