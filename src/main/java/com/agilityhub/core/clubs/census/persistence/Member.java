package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
public class Member extends CensusEntity {
    public String accountId;
    public Integer memberNumber;
    public Map<String,Object> idDocument;
    public String firstName;
    public String lastName1;
    public String lastName2;
    public String gender;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate birthDate;
    public List<Map<String,Object>> contactEmails;
    public List<Map<String,Object>> phones;
    public Map<String,Object> address;
    public Map<String,Object> paymentMethod;
    public String planId;
    public String priceId;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate nextInvoiceDate;
    public Map<String,Object> consents;
    public String remarks;
    public String internalNotes;
    public String status;
    public Instant joinedAt;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate leaveDate;
    public String leaveRequestId;
    public Map<String,Object> bookingBlock;
    public String lastDogForClass;
    public String lastDogForTraining;
    public String familyGroupId;
    public Map<String,Object> notificationPreferences;
    public Instant erasedAt;
    public String erasureRequestId;
}
