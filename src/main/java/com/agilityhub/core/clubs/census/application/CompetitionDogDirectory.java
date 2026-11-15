package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Dog;
import java.util.List;
import java.util.Objects;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/** A minimal public census projection for the competition context. */
@Service
public class CompetitionDogDirectory {
    private final CensusRepository<Dog> dogs;
    private final CensusAccess access;
    public CompetitionDogDirectory(CensusRepository<Dog> dogs, CensusAccess access) { this.dogs = dogs; this.access = access; }
    public record LicensedDog(String id, String name, String license) { }
    public List<LicensedDog> list(String organisation) {
        return dogs.matching(Criteria.where("status").is("ACTIVE")).stream()
                .flatMap(dog -> Objects.requireNonNullElse(dog.licenses, List.<java.util.Map<String, Object>>of()).stream()
                        .filter(license -> organisation.equalsIgnoreCase(Objects.toString(license.get("organisation"), "")))
                        .map(license -> new LicensedDog(dog.id, dog.name, Objects.toString(license.get("number"), ""))))
                .filter(dog -> !dog.license().isBlank()).toList();
    }
    public void requireMutable(String id) { CensusAccess.unfrozen(access.mutableDog(id)); }
}
