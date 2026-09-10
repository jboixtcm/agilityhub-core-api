package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("dogs")
public class Dog extends CensusEntity {
    public Map<String,Object> sourceIds;
    public Map<String,Object> signup;
    public Map<String,Object> externalIds;
    public String memberId;
    public String name;
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
}
