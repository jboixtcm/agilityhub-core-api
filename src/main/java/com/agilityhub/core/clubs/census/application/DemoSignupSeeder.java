package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.clubs.signup.application.SignupPolicy;
import com.agilityhub.core.payments.application.UpfrontPayments;
import com.agilityhub.core.platform.application.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Called only inside the guarded demo transaction; fixtures send no applicant notifications. */
@Component
class DemoSignupSeeder {
    private final CensusAccess census;
    private final CensusRepository<DogDocument> documents;
    private final SignupPolicy policy;
    private final MigrationCatalogAccess catalogs;
    private final CensusClubSettings settings;
    private final UpfrontPayments payments;
    private final DocumentService documentService;

    DemoSignupSeeder(CensusAccess census, CensusRepository<DogDocument> documents, SignupPolicy policy,
            MigrationCatalogAccess catalogs, CensusClubSettings settings, UpfrontPayments payments, DocumentService documentService) {
        this.census = census; this.documents = documents; this.policy = policy; this.catalogs = catalogs;
        this.settings = settings; this.payments = payments; this.documentService = documentService;
    }

    void apply(Member member, DemoDataset.PendingSignup row, int ordinal, List<String> requiredDocuments) {
        var config = census.config();
        String locale = config.club().defaultLocale();
        String version = settings.signupLegal(locale).get("legalTextsVersion").toString();
        String planId = catalogs.rows("plans").stream().filter(p -> row.planCode().equals(p.get("code")))
                .findFirst().orElseThrow(() -> new com.agilityhub.core.shared.domain.ApiException(
                        com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND)).get("_id").toString();
        LocalDate submitted = policy.today().minusDays(row.daysAgo());
        Instant at = submitted.atStartOfDay(ZoneId.of(config.club().timeZone())).toInstant();
        member.memberNumber = null; member.joinedAt = null; member.planId = null; member.priceId = null;
        member.gender = "FEMALE";
        member.idDocument = object("type", "PASSPORT", "number", "DEMO" + (ordinal + 1));
        member.familyGroupClaim = object("status", "NONE");
        member.signup = object("submittedAt", at, "locale", locale, "source", "PUBLIC", "readmission", false,
                "planIdRequested", planId, "firstMonthOption", "TODAY");
        member.paymentMethod = object("type", "SEPA_DD", "holderName", member.firstName + " " + member.lastName1,
                "iban", row.accountProvided() ? DemoDataset.iban(ordinal + 1) : null, "mandateSignedAt", at);
        member.consents = new ConsentLedgerConverter().read(List.of(
                object("type", "PRIVACY_POLICY", "granted", true, "version", version, "acceptedAt", at, "locale", locale, "source", "DEMO_SEED"),
                object("type", "IMAGE_USE", "granted", true, "version", version, "acceptedAt", at, "locale", locale, "source", "DEMO_SEED")), null);
        census.members.save(member);
        var dog = new Dog(); dog.id = DemoDataset.id(member.clubId, "pending-dog", ordinal);
        dog.clubId = member.clubId; dog.memberId = member.id; dog.name = row.dogName(); dog.chip = row.chip();
        dog.status = "PENDING"; dog.sex = "FEMALE"; dog.breed = "Fictional mixed breed";
        dog.birthDate = submitted.minusYears(2).withDayOfMonth(1); dog.signup = new LinkedHashMap<>(member.signup);
        census.dogs.insert(dog);
        for (String type : requiredDocuments) {
            var document = new DogDocument(); document.id = documentService.documentId(dog.id, type);
            document.clubId = member.clubId; document.dogId = dog.id; document.type = type;
            document.state = "PENDING"; document.files = List.of(); documents.insert(document);
        }
        var quote = policy.quote(planId, dog.id, false, null, "TODAY", submitted);
        payments.create(member.id, quote.lines().stream().map(l -> new UpfrontPayments.Charge(l.concept(), l.dogId(), l.amountDue())).toList());
    }
}
