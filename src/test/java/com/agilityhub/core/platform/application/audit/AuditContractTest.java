package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.support.AuditCovers;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AuditContractTest {
    @Test void T_14_12_everyActionHasAnAnnotatedExecutableTest() {
        var covered = EnumSet.noneOf(AuditAction.class);
        var evidence = new java.util.TreeMap<AuditAction, java.util.SortedSet<String>>();
        for (Class<?> type : ReflectionSupport.findAllClassesInPackage("com.agilityhub.core", candidate -> true, name -> true)) {
            for (Method method : type.getDeclaredMethods()) {
                AuditCovers annotation = method.getAnnotation(AuditCovers.class);
                if (annotation == null) { continue; }
                assertThat(AnnotationSupport.isAnnotated(method, org.junit.platform.commons.annotation.Testable.class))
                        .as("@AuditCovers must annotate an executable JUnit test: %s", method).isTrue();
                assertThat(type.getSimpleName()).as("Covered tests must run under Surefire/Failsafe")
                        .matches("(Test.*|.*Tests?|.*TestCase|.*IT|IT.*|.*ITCase)");
                assertThat(AnnotationSupport.isAnnotated(method, org.junit.jupiter.api.Disabled.class)
                        || AnnotationSupport.isAnnotated(type, org.junit.jupiter.api.Disabled.class))
                        .as("@AuditCovers test must not be disabled: %s", method).isFalse();
                covered.addAll(Arrays.asList(annotation.value()));
                for (AuditAction action : annotation.value()) {
                    evidence.computeIfAbsent(action, ignored -> new java.util.TreeSet<>()).add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        evidence.forEach((action, tests) -> System.out.println("Audit coverage: " + action + " -> " + String.join(", ", tests)));
        var uncovered = EnumSet.allOf(AuditAction.class);
        uncovered.removeAll(covered);
        assertThat(uncovered).as("AuditAction values without @AuditCovers tests: %s", uncovered).isEmpty();
    }
}
