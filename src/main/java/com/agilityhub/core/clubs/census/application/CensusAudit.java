package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import com.agilityhub.core.shared.domain.audit.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.context.annotation.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Configuration
public class CensusAudit {
    /**
     * {@code iban} is audited masked («···· ···· ···· ···· 1332», S03 R-03-07) for both shapes of an account: the clear `iban`
     * written before E11-T02, or a sealed one (INC-31: `ibanEncrypted` + `ibanLast4`), whose last digits stand for it. Its
     * {@code ibanEncrypted} is audited hidden, so a new account with the same last digits still shows as a change, never its
     * value. A cleared account (E42) audits as `null`.
     */
    public record Bank(@AuditField @Sensitive String iban, @AuditField String type, @AuditField String holderName,
            @AuditField @Sensitive(Sensitive.Strategy.MASK_ID_DOCUMENT) String holderTaxId, @AuditField String channel,
            @AuditField String last4, @AuditField String brand,
            @AuditField Boolean invalid, @AuditField @Sensitive(Sensitive.Strategy.HIDE) String ibanEncrypted) { }
    public record IdentityDocument(@AuditField String type, @AuditField @Sensitive(Sensitive.Strategy.MASK_ID_DOCUMENT) String number) { }
    @Bean AuditableLoader censusMemberAudit(CensusRepository<Member> repo, ObjectMapper mapper) { return loader("Member", repo, mapper); }
    @Bean AuditableLoader censusDogAudit(CensusRepository<Dog> repo, ObjectMapper mapper) { return loader("Dog", repo, mapper); }
    @Bean AuditableLoader censusFamilyAudit(CensusRepository<FamilyGroup> repo, ObjectMapper mapper) { return loader("FamilyGroup", repo, mapper); }
    @Bean AuditableLoader censusDocumentAudit(CensusRepository<DogDocument> repo, ObjectMapper mapper) { return loader("DogDocument", repo, mapper); }
    private AuditableLoader loader(String name, CensusRepository<?> repo, ObjectMapper mapper) {
        return new AuditableLoader() {
            public String entityType() { return name; }
            public Object load(String id) { var item = repo.findById(id).orElse(null); return item == null ? null : view(item, mapper); }
        };
    }
    /** The audited view of a census entity: bank data and identity documents are masked by the audit writer (R-14-09). */
    static Map<String,Object> view(Object item, ObjectMapper mapper) {
        Map<String,Object> fields = mapper.convertValue(item, new TypeReference<>() { });
        for (String key : List.of("id", "clubId", "version", "createdAt", "updatedAt", "freeTrainingAllowed")) { fields.remove(key); }
        if (item instanceof Member member) {
            fields.put("paymentMethod", bank(member.paymentMethod));
            fields.put("idDocument", new IdentityDocument(string(map(member.idDocument).get("type")), string(map(member.idDocument).get("number"))));
            // E38: the payment method a readmission submitted is masked like the record's.
            var submitted = map(map(member.readmissionRequest).get("submitted"));
            if (submitted.containsKey("paymentMethod")) {
                var request = new LinkedHashMap<>(map(fields.get("readmissionRequest"))); var values = new LinkedHashMap<>(submitted);
                values.put("paymentMethod", bank(map(submitted.get("paymentMethod")))); request.put("submitted", values); fields.put("readmissionRequest", request);
            }
        }
        return fields;
    }
    /** The audited stand-in of a sealed account: masked, only its last four digits show. */
    private static final String SEALED = "sealed-";
    static Bank bank(Map<String,Object> payment) {
        var pay = map(payment); var sepa = map(pay.getOrDefault("sepa", pay)); var card = map(pay.getOrDefault("card", pay));
        String iban = sepa.get("iban") != null ? string(sepa.get("iban")) : sepa.get("ibanLast4") == null ? null : SEALED + sepa.get("ibanLast4");
        return new Bank(iban, string(pay.get("type")), string(sepa.get("holderName")),
                string(sepa.get("holderTaxId")), string(pay.get("channel")), string(card.get("last4")), string(card.get("brand")),
                "CARD".equals(pay.get("type")) ? Boolean.TRUE.equals(card.get("invalid")) : null, string(sepa.get("ibanEncrypted")));
    }
}
