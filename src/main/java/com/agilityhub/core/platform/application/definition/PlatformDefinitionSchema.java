package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** S17: extend the published WP-A shape with environment references, never resolve them in a contract stub. */
@Component
public class PlatformDefinitionSchema {
    private final ObjectMapper mapper;
    private final ObjectNode document;
    private final JsonSchema validator;
    public PlatformDefinitionSchema(ObjectMapper mapper) throws IOException {
        this.mapper=mapper;
        ObjectNode source;
        try(var input=new ClassPathResource("seeds/club-definition.schema.json").getInputStream()) {
            source=(ObjectNode)mapper.readTree(input);
        }
        document=(ObjectNode)inline(source,source.path("$defs"));
        document.remove(java.util.List.of("$defs","$id"));
        var properties=(ObjectNode)document.path("properties");
        ((com.fasterxml.jackson.databind.node.ArrayNode)properties.path("preset").path("enum")).add("COMPLET");
        ((ObjectNode)properties.path("catalogs").path("properties")).set("fromTemplate",
                mapper.readTree("{\"type\":\"string\",\"minLength\":1,\"description\":\"S17 template club reference; resolved by the implementation task.\"}"));
        // A console definition is portable and cannot contain seed passwords or local files.
        properties.remove("accounts");
        ((ObjectNode)properties.path("pages").path("items").path("properties")).remove("bodyFile");
        ((ObjectNode)properties.path("pages").path("items")).remove("anyOf");
        ((com.fasterxml.jackson.databind.node.ArrayNode)properties.path("pages").path("items").path("required")).add("body");
        ((ObjectNode)properties.path("paymentProviders")).put("description","Provider switches and optional configuration; credentials use environment references only.");
        var providers=(ObjectNode)properties.path("paymentProviders").path("oneOf").get(1).path("properties");
        for(String provider:java.util.List.of("STRIPE","SEPA_XML")) ((ObjectNode)providers.path(provider)).put("description","Console configuration with environment references; omitted credentials preserve existing values.");
        var stripe=(ObjectNode)providers.path("STRIPE").path("properties");
        stripe.set("secretKey",env()); stripe.set("webhookSecret",env());
        stripe.set("publishableKey",mapper.readTree("{\"type\":\"string\"}"));
        stripe.set("mode",mapper.readTree("{\"type\":\"string\",\"enum\":[\"test\",\"live\"]}"));
        var sepa=(ObjectNode)providers.path("SEPA_XML").path("properties");
        sepa.set("iban",env());
        for(String field:java.util.List.of("creditorName","creditorId","suffix","invoiceSeriesPattern")) sepa.set(field,mapper.readTree("{\"type\":\"string\"}"));
        document.put("description","S17 ClubDefinition: WP-A's canonical layout (localization inside club). Console credentials accept environment references only; accounts/passwords and local page files are excluded. No environment lookup occurs in E10-T01.");
        validator=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
    }
    private JsonNode env() throws IOException {
        return mapper.readTree("{\"type\":\"object\",\"properties\":{\"env\":{\"type\":\"string\",\"pattern\":\"^[A-Z][A-Z0-9_]*$\"}},\"required\":[\"env\"],\"additionalProperties\":false}");
    }
    private JsonNode inline(JsonNode node,JsonNode definitions) {
        if(node.isObject()) {
            if(node.has("$ref")) return inline(definitions.path(node.path("$ref").asText().substring("#/$defs/".length())),definitions);
            var result=mapper.createObjectNode();
            node.properties().forEach(entry->result.set(entry.getKey(),inline(entry.getValue(),definitions)));
            return result;
        }
        if(node.isArray()) { var result=mapper.createArrayNode(); node.forEach(value->result.add(inline(value,definitions))); return result; }
        return node.deepCopy();
    }
    public JsonNode document() { return document.deepCopy(); }
    public void validate(Map<String,Object> request) {
        var errors=validator.validate(mapper.valueToTree(request));
        if(!errors.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("fieldErrors",
                errors.stream().map(error->Map.of("field",field(error),"code",
                        MISSING.contains(error.getType())?"REQUIRED":"INVALID_VALUE"))
                        .distinct().sorted(java.util.Comparator.comparing(value->value.get("field"))).toList()));
    }
    /** A missing field is `REQUIRED`, the codebase's one code for it (CONVENCIONS_API §6). */
    private static final java.util.Set<String> MISSING=java.util.Set.of("required","dependentRequired");
    /** Keywords reported at the parent object: the offending property is appended, so the field names it (`accounts`, `club.foo`). */
    private static final java.util.Set<String> PROPERTY=java.util.Set.of("required","dependentRequired","additionalProperties","unevaluatedProperties");
    private String field(ValidationMessage error) {
        var path=error.getInstanceLocation();
        if(PROPERTY.contains(error.getType())&&error.getProperty()!=null) path=path.append(error.getProperty());
        var field=new StringBuilder();
        for(int i=0;i<path.getNameCount();i++) {
            Object part=path.getElement(i);
            if(part instanceof Number) field.append('[').append(part).append(']');
            else { if(!field.isEmpty()) field.append('.'); field.append(part); }
        }
        return field.toString();
    }
}
