package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("family_groups")
public class FamilyGroup extends CensusEntity {
    public String holderMemberId;
    public List<String> memberIds;
    public String status;
}
