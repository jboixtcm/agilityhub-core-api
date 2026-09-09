package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("dog_documents")
public class DogDocument extends CensusEntity {
    public String dogId;
    public String type;
    public String state;
    public List<Map<String,Object>> files;
    public Instant lastReminderAt;
}
