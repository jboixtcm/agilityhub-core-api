package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.ParameterDefinition;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

@Component
public class ParameterCatalog {
    private final Map<String, ParameterDefinition> definitions;
    public ParameterCatalog(ObjectMapper mapper) {
        try (var input = new ClassPathResource("parameters/catalog.yaml").getInputStream()) {
            Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            Map<String, ParameterDefinition> entries = new LinkedHashMap<>();
            for (Object value : (List<?>) yaml.get("parameters")) {
                var entry = mapper.convertValue(value, ParameterDefinition.class);
                if (entries.put(entry.key(), entry) != null) { throw new IllegalStateException("Duplicate parameter: " + entry.key()); }
            }
            definitions = Collections.unmodifiableMap(entries);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public int defaultInteger(String key) { return ((Number) get(key).defaultValue()).intValue(); }
    public Object defaultValue(String key) { return get(key).defaultValue(); }
    public Map<String, ParameterDefinition> entries() { return definitions; }
    public ParameterDefinition get(String key) {
        var entry = definitions.get(key);
        if (entry == null) { throw new ApiException(ErrorCode.UNKNOWN_PARAMETER); }
        return entry;
    }
}
