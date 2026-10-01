package com.agilityhub.core.payments.api;

import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * S12 R-12-25 (E8-T02, T-12-08's integration half): the consumers of S10 `AttendanceMarked` and S08 `BookingCancelled{late}`
 * through the real outbox — a `CHARGE_ON_ATTENDANCE` class marked PRESENT becomes one `PendingCharge` at the `SINGLE_CLASS`
 * price on the class date with its description frozen in the club's language, the booking names it; marked back to PENDING
 * it is voided; a redelivery or a second mark never duplicates it; a late cancellation charges; SINGLE_CLASS off does nothing;
 * a PAY_TO_BOOK booking is not this task's. The next run bills the charge (R-12-11).
 */
class PendingChargesIT extends BillingItSupport {
    @Autowired EventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    static final Instant CLASS_STARTS = Instant.parse("2026-10-06T16:50:00Z"); // Tuesday 6 October, 18:50 in Madrid

    @BeforeEach void bookings() {
        mongo.remove(Query.query(Criteria.where("memberId").is("mas")), "pending_charges");
        mongo.save(new Document("_id", "bill-dog-mas").append("clubId", CLUB).append("memberId", "mas").append("name", "Duna").append("sex", "FEMALE")
                .append("status", "ACTIVE").append("version", 0), "dogs");
        booking("bill-b-attended", "CHARGE_ON_ATTENDANCE");
        booking("bill-b-late", "CHARGE_ON_ATTENDANCE");
        booking("bill-b-paid", "PAY_TO_BOOK");
        // Mia's next invoice date is in November: October's attendances are billed by the November run.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("mas")), new org.springframework.data.mongodb.core.query.Update().set("nextInvoiceDate", "2026-11-01"), "members");
    }
    void booking(String id, String mode) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("classSessionId", "bill-class").append("dogId", "bill-dog-mas").append("memberId", "mas")
                .append("state", "ACTIVE").append("origin", "APP").append("bookedAt", Date.from(NOW)).append("classStartsAt", Date.from(CLASS_STARTS))
                .append("classEndsAt", Date.from(CLASS_STARTS.plusSeconds(3600))).append("bookingWeekKey", "2026-10-05")
                .append("charge", new Document("mode", mode).append("price", new Document("amountMinor", 1200L).append("currency", "EUR"))).append("version", 0)
                .append("createdAt", Date.from(NOW)), "bookings");
    }
    void attendance(String bookingId, String state, String previous) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookingId", bookingId); payload.put("classSessionId", "bill-class"); payload.put("dogId", "bill-dog-mas"); payload.put("memberId", "mas");
        payload.put("state", state); payload.put("previousState", previous); payload.put("by", Map.of("accountId", "bill-instructor", "role", "INSTRUCTOR"));
        publish(new AttendanceEvent(AttendanceEvent.Kind.AttendanceMarked, CLUB, "bill-attendance-" + bookingId, clock.instant(), payload, "bill-instructor", null,
                DomainEvent.Origin.INSTRUCTOR));
    }
    void publish(DomainEvent event) {
        try (var tenant = TenantContext.open(CLUB)) { new TransactionTemplate(transactions).executeWithoutResult(status -> publisher.publish(event)); }
        outbox.dispatch();
    }
    List<Document> charges() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("memberId").is("mas")), Document.class, "pending_charges"); }
    String chargeRef(String bookingId) {
        var charge = mongo.findById(bookingId, Document.class, "bookings").get("charge", Document.class);
        return charge.getString("chargeInvoiceLineRef");
    }

    @Test void T_12_08_presentChargesOnceAtTheClassDatePriceVoidsBackToPendingAndALateCancellationCharges() throws Exception {
        attendance("bill-b-attended", "PRESENT", "PENDING");
        assertThat(charges()).singleElement().satisfies(charge -> {
            assertThat(charge.getString("bookingId")).isEqualTo("bill-b-attended");
            assertThat(charge.get("amount", Document.class).getLong("amountMinor")).isEqualTo(1200L);
            assertThat(charge.getString("priceId")).isEqualTo("bill-price-single-single_class");
            assertThat(charge.getString("description")).isEqualTo("Classe 06/10 — Duna");
            assertThat(charge.get("voidedAt")).isNull();
        });
        String chargeId = charges().getFirst().getString("_id");
        assertThat(chargeRef("bill-b-attended")).isEqualTo(chargeId);
        // A second PRESENT (a redelivery or another mark) never duplicates it.
        attendance("bill-b-attended", "PRESENT", "PENDING");
        attendance("bill-b-attended", "NO_SHOW", "PRESENT");
        assertThat(charges()).hasSize(1);
        // Back to PENDING before billing (S10 R-10-07): voided; marked again: the same charge comes back.
        attendance("bill-b-attended", "PENDING", "NO_SHOW");
        assertThat(charges().getFirst().get("voidedAt")).isNotNull();
        attendance("bill-b-attended", "PRESENT", "PENDING");
        assertThat(charges()).singleElement().satisfies(charge -> {
            assertThat(charge.getString("_id")).isEqualTo(chargeId);
            assertThat(charge.get("voidedAt")).isNull();
        });
        // A late cancellation is charged; an in-time one is not; a PAY_TO_BOOK booking (E8-T04) is left alone.
        publish(new BookingEvent(BookingEvent.Kind.BookingCancelled, CLUB, "bill-b-late", clock.instant(), Map.of("bookingId", "bill-b-late", "late", true, "by", "MEMBER"),
                "bill-mia", null, DomainEvent.Origin.APP));
        publish(new BookingEvent(BookingEvent.Kind.BookingCancelled, CLUB, "bill-b-paid", clock.instant(), Map.of("bookingId", "bill-b-paid", "late", true, "by", "MEMBER"),
                "bill-mia", null, DomainEvent.Origin.APP));
        attendance("bill-b-paid", "PRESENT", "PENDING");
        assertThat(charges()).extracting(charge -> charge.getString("bookingId")).containsExactlyInAnyOrder("bill-b-attended", "bill-b-late");
        assertThat(chargeRef("bill-b-paid")).isNull();
        // The November run bills them on Mia's invoice, one SINGLE_CLASS line each.
        clock.setInstant(Instant.parse("2026-10-26T09:00:00Z"));
        run("2026-11", simulate("2026-11").path("id").asText());
        var mia = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("mas")).findFirst().orElseThrow();
        assertThat(mia.getList("lines", Document.class)).extracting(line -> line.getString("origin") + " " + line.getString("description"))
                .containsExactlyInAnyOrder("SINGLE_CLASS Classe 06/10 — Duna", "SINGLE_CLASS Classe 06/10 — Duna");
        assertThat(charges()).allSatisfy(charge -> assertThat(charge.getString("invoiceId")).isEqualTo(mia.getString("_id")));
        // Once billed, a mark back to PENDING never changes the charge (the invoice is immutable: an adjustment corrects it).
        attendance("bill-b-attended", "PENDING", "PRESENT");
        assertThat(charges()).allSatisfy(charge -> assertThat(charge.get("voidedAt")).isNull());
    }

    @Test void T_12_22_withoutSingleClassAnAttendanceChargesNothing() {
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.SINGLE_CLASS);
        modules(CLUB, modules);
        attendance("bill-b-attended", "PRESENT", "PENDING");
        assertThat(charges()).isEmpty();
        assertThat(chargeRef("bill-b-attended")).isNull();
    }

    @Test void R_12_25_withoutACurrentSingleClassPriceNoAmountIsInvented() {
        mongo.remove(Query.query(Criteria.where("_id").is("bill-price-single-single_class")), "prices");
        attendance("bill-b-attended", "PRESENT", "PENDING");
        assertThat(charges()).isEmpty();
    }
}
