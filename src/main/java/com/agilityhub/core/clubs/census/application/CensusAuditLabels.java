package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.platform.application.audit.AuditLabeler;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.annotation.*;

/**
 * E11-T10 (INC-38), S14 §3: the census' `entityLabel` snapshots, the member's full name («Laura Serra Vidal») and the dog's name.
 * An erased member or dog is labelled with its pseudonym («Abonat suprimit», R-14-15), and the erasure removes the labels of the
 * entries written before.
 */
@Configuration
public class CensusAuditLabels {
    @Bean AuditLabeler censusMemberLabel(CensusRepository<Member> members) {
        return labeler("Member", id -> members.findById(id).map(CensusAuditLabels::fullName).orElse(null));
    }
    @Bean AuditLabeler censusDogLabel(CensusRepository<Dog> dogs) {
        return labeler("Dog", id -> dogs.findById(id).map(dog -> dog.name).orElse(null));
    }
    static String fullName(Member member) {
        String name = Stream.of(member.firstName, member.lastName1, member.lastName2).filter(Objects::nonNull).map(String::strip)
                .filter(part -> !part.isEmpty()).collect(Collectors.joining(" "));
        return name.isEmpty() ? null : name;
    }
    private static AuditLabeler labeler(String type, java.util.function.Function<String, String> label) {
        return new AuditLabeler() {
            @Override public String entityType() { return type; }
            @Override public String label(String entityId) { return label.apply(entityId); }
        };
    }
}
