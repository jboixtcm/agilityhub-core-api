package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ModuleDependencyValidator {
    /** Validates a complete configuration, including presets and bulk imports. */
    public void validate(Set<Module> enabledModules) {
        Set<Module> missing = EnumSet.noneOf(Module.class);
        enabledModules.forEach(module -> missing.addAll(module.dependsOn()));
        missing.removeAll(enabledModules);
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.MODULE_DEPENDENCY,
                    Map.of("missing", missing.stream().map(Enum::name).toList()));
        }
    }

    /** Validates a proposed toggle without modifying the caller's configuration. */
    public void validateChange(Set<Module> enabledModules, Module module, boolean enabled) {
        Set<Module> proposed = EnumSet.noneOf(Module.class);
        proposed.addAll(enabledModules);
        if (enabled) {
            proposed.add(module);
        } else {
            proposed.remove(module);
            var dependents = proposed.stream().filter(candidate -> candidate.dependsOn().contains(module))
                    .map(Enum::name).toList();
            if (!dependents.isEmpty()) {
                throw new ApiException(ErrorCode.MODULE_DEPENDENCY, Map.of("dependents", dependents));
            }
        }
        validate(proposed);
    }
}
