package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
public class Member extends CensusEntity {
    public Map<String,Object> sourceIds;
    public Map<String,Object> externalIds;
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
    @org.springframework.data.convert.ValueConverter(ConsentLedgerConverter.class)
    public Map<String,Object> consents;
    public Map<String,Object> signup;
    /**
     * R-04-06 (E38): a pending readmission. `submitted` holds what the applicant sent (person, contacts, address, payment
     * method, new consent entries) until validation applies it; `previous` holds what a rejection restores (status,
     * `leftAt`, `leftReason`, `leaveDate`, claim and signup block of the LEFT record). Absent otherwise.
     */
    public Map<String,Object> readmissionRequest;
    public Map<String,Object> familyGroupClaim;
    public String remarks;
    public String internalNotes;
    public String status;
    public Instant joinedAt;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate leaveDate;
    public Instant leftAt;
    public String leftReason;
    public String leaveRequestId;
    public Map<String,Object> bookingBlock;
    @ForeignOwned public String lastDogForClass;
    @ForeignOwned public String lastDogForTraining;
    public String familyGroupId;
    public Map<String,Object> notificationPreferences;
    public Instant erasedAt;
    public String erasureRequestId;
}
