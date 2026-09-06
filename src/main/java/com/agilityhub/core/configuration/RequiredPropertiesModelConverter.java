package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Derives property presence from Java models after swagger has resolved names and references. */
final class RequiredPropertiesModelConverter implements ModelConverter {
    private static final Set<String> NULLABLE = Set.of("org.springframework.lang.Nullable", "org.jspecify.annotations.Nullable");
    private final ObjectMapper mapper;

    RequiredPropertiesModelConverter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) { return null; }
        Schema<?> resolved = chain.next().resolve(type, context, chain);
        if (resolved == null || type.getType() == null) { return resolved; }
        Schema<?> model = resolved;
        if (model.get$ref() != null) {
            model = context.getDefinedModels().get(model.get$ref().substring(model.get$ref().lastIndexOf('/') + 1));
        }
        if (model == null || model.getProperties() == null) { return resolved; }

        var javaType = mapper.constructType(type.getType());
        var inputProperties = mapper.getDeserializationConfig().introspect(javaType).findProperties();
        var required = new TreeSet<String>();
        if (model.getRequired() != null) { required.addAll(model.getRequired()); }
        for (var property : mapper.getSerializationConfig().introspect(javaType).findProperties()) {
            if (property.getPrimaryMember() == null) {
                String internalName = property.getInternalName();
                property = inputProperties.stream().filter(input -> input.getInternalName().equals(internalName))
                        .findFirst().orElse(property);
            }
            var member = property.getPrimaryMember();
            var annotation = member == null ? null : member.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            String name = annotation != null && !annotation.name().isBlank() ? annotation.name() : property.getName();
            if (!model.getProperties().containsKey(name)) { continue; }
            if (optional(property, annotation)) { required.remove(name); }
            else { required.add(name); }
        }
        model.setRequired(new ArrayList<>(required));
        return resolved;
    }

    private static boolean optional(BeanPropertyDefinition property, io.swagger.v3.oas.annotations.media.Schema annotation) {
        if (Optional.class.isAssignableFrom(property.getPrimaryType().getRawClass())) { return true; }
        if (annotation != null && (annotation.requiredMode() == io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED
                || annotation.nullable())) { return true; }
        return nullable(property.getPrimaryMember()) || nullable(property.getField()) || nullable(property.getGetter());
    }

    private static boolean nullable(AnnotatedMember member) {
        if (member == null) { return false; }
        for (Annotation annotation : member.annotations()) {
            if (NULLABLE.contains(annotation.annotationType().getName())) { return true; }
        }
        // JSpecify annotates the top-level type use, not the field/method declaration.
        var type = switch (member.getMember()) {
            case Field field -> field.getAnnotatedType();
            case Method method -> method.getAnnotatedReturnType();
            default -> null;
        };
        return type != null && Arrays.stream(type.getAnnotations())
                .anyMatch(annotation -> NULLABLE.contains(annotation.annotationType().getName()));
    }
}
