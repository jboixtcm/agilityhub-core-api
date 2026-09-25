package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("dogs")
public class Dog extends CensusEntity {
    public Map<String,Object> sourceIds;
    public Map<String,Object> signup;
    /**
     * R-04-06 (E38, E3-T17): the reused dog of a pending readmission, like `Member.readmissionRequest`. `submitted {name, sex,
     * breed, birthDate, instructorNote, documents[{type, files[]}]}` waits for the validation; `previous {status,
     * deactivatedAt, deactivationReason, signup}` is what a rejection restores. Null otherwise.
     */
    public Map<String,Object> readmissionRequest;
    public Map<String,Object> externalIds;
    public String memberId;
    public String name;
    /**
     * R-04-12 (E3-T09 round 2): the family lookup's key, {@link #nameKey(String)} of {@link #name}. Written by every census
     * write of the dog ({@link CensusRepository}, {@link CensusMigrationRepository}); compared with
     * {@link CensusRepository#NAME_COLLATION}, so case and accents do not count either.
     */
    public String nameKey;
    public String breed;
    public String sex;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate birthDate;
    public String chip;
    public String handlerName;
    public String photoFileKey;
    public String levelId;
    public Instant levelAssignedAt;
    public List<Map<String,Object>> levelHistory;
    public Boolean freeTrainingOverride;
    public Boolean freeTrainingAllowed;
    public Map<String,Object> instructorNote;
    public String remarks;
    public List<Map<String,Object>> licenses;
    public String status;
    public Instant registeredAt;
    public Instant deactivatedAt;
    public String deactivationReason;
    /** R-04-12 normalisation of a dog's name: trimmed, inner whitespace collapsed to one space. */
    public static String nameKey(String name) { return name==null?null:name.strip().replaceAll("(?U)\\s+"," "); }
    @Override void beforeWrite() { nameKey = nameKey(name); }
}
