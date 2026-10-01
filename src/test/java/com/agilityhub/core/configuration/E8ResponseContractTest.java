package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.api.LifecycleContracts;
import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.payments.api.BillingContracts;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

/**
 * E8-T01 (S12 WP-12-A, S13 WP-13-A): the S12/S13 §6 forms round-trip every documented field, nulls included (the api sends a
 * nullable field as `null`; only the provider maps and the module-dependent booking counters leave a key out); the S12 §6
 * simulation JSON and the S13 §6 `GET /me/inactivity-periods` JSON are the fixtures and round-trip; the §7 events carry
 * exactly their payloads; the notification fixtures carry exactly the catalog variables; the wire enums are the specs'
 * lists; the `billing.*`, `inactivity.*` and `leave.*` parameters carry the Cànic defaults.
 */
class E8ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static Class<?> type(String fixtureKey) throws ClassNotFoundException {
        String name = fixtureKey.split("#")[0];
        try { return Class.forName(BillingContracts.class.getName() + "$" + name); }
        catch (ClassNotFoundException lifecycle) { return Class.forName(LifecycleContracts.class.getName() + "$" + name); }
    }
    private JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/" + name + ".json")) { return mapper.readTree(input); }
    }
    private void roundTrip(String label, JsonNode json, Class<?> type) throws Exception {
        Object value = mapper.treeToValue(json, type);
        JSONAssert.assertEquals(label, json.toString(), mapper.writeValueAsString(value), true);
    }

    @Test void T_12_21_T_13_24_formsKeepEveryDocumentedFieldDuringRoundTrip() throws Exception {
        int forms = 0;
        for (String group : List.of("e8-billing-responses", "e8-lifecycle-responses")) {
            var fields = fixture(group).fields();
            while (fields.hasNext()) { var entry = fields.next(); roundTrip(entry.getKey(), entry.getValue(), type(entry.getKey())); forms++; }
        }
        assertThat(forms).isEqualTo(41);
        // A provider the club does not use, and a counter of a module that is off, are absent; nothing else is.
        var byProvider = mapper.valueToTree(new BillingContracts.ByProvider(null, null, null));
        assertThat(byProvider.size()).isZero();
        var inside = mapper.valueToTree(new LifecycleContracts.BookingsInside(1, null, null, null, 1));
        assertThat(inside.fieldNames()).toIterable().containsExactly("classes", "total");
        var nulls = mapper.valueToTree(new BillingContracts.CashMember("member-a", "Example", null));
        assertThat(nulls.has("plannedLeaveDate") && nulls.path("plannedLeaveDate").isNull()).isTrue();
    }

    /** S12 §6 «JSON de la simulació (resum)» is the fixture, plus the `id` that `POST /billing/runs` names, and round-trips. */
    @Test void T_12_09_theS12SimulationJsonIsTheFixtureAndRoundTrips() throws Exception {
        String s12 = Files.readString(Path.of("docs/specs/S12-facturacio-i-pagaments.md"));
        var fixture = (com.fasterxml.jackson.databind.node.ObjectNode) fixture("e8-simulation");
        var withoutId = fixture.deepCopy(); withoutId.remove("id");
        JSONAssert.assertEquals(extract(s12, "**JSON de la simulació (resum)**:"), withoutId.toString(), true);
        assertThat(fixture.fieldNames()).toIterable().contains("id");
        roundTrip("BillingSimulation", fixture, BillingContracts.BillingSimulation.class);
        assertThat(fixture.at("/kpis/byProvider").has("STRIPE")).as("the Cànic has no Stripe: the key is absent").isFalse();
    }

    /** S13 §6 `GET /me/inactivity-periods` (24-09-2026, Laura) is the fixture byte for byte in JSON terms, and round-trips. */
    @Test void T_13_08_theS13ContextJsonIsTheFixtureAndRoundTrips() throws Exception {
        String s13 = Files.readString(Path.of("docs/specs/S13-inactivitat-i-baixa.md"));
        JSONAssert.assertEquals(extract(s13, "`GET /me/inactivity-periods` (24-09-2026"), fixture("e8-me-inactivity-periods").toString(), true);
        roundTrip("MeInactivityContext", fixture("e8-me-inactivity-periods"), LifecycleContracts.MeInactivityContext.class);
        assertThat(fixture("e8-me-inactivity-periods").at("/periods/0/toMonth").isNull()).as("an open period sends toMonth: null").isTrue();
    }
    private static String extract(String spec, String heading) {
        int start = spec.indexOf("```json", spec.indexOf(heading)) + "```json".length();
        return spec.substring(start, spec.indexOf("```", start));
    }

    /** R-12-12, T-12-11 (contract half), S12 §3 Remittance: no response form carries a full IBAN, a storage key or a provider secret. */
    @Test void T_12_11_noResponseFormCarriesAFullIbanAStorageKeyOrAProviderSecret() throws Exception {
        var forbidden = Set.of("iban", "ibanEncrypted", "fileKey", "secretKeyEnc", "webhookSecretEnc", "payloadHash", "previousDates", "holder");
        for (var container : List.of(BillingContracts.class, LifecycleContracts.class)) {
            for (var form : container.getDeclaredClasses()) {
                if (!form.isRecord()) { continue; }
                assertThat(Arrays.stream(form.getRecordComponents()).map(RecordComponent::getName)).as(form.getSimpleName()).doesNotContainAnyElementsOf(forbidden);
            }
        }
        assertThat(Arrays.stream(BillingContracts.Creditor.class.getRecordComponents()).map(RecordComponent::getName)).contains("maskedIban");
        // CLUB.paymentProviders.STRIPE: the secrets never serialize, nor print.
        var stripe = StripeProviderSettings.of(Map.of("STRIPE", Map.of("enabled", true, "publishableKey", "pk_test_e8", "secretKeyEnc", "ENC-SECRET",
                "webhookSecretEnc", "ENC-WEBHOOK", "mode", "test", "accountId", "acct_e8")));
        assertThat(stripe.enabled()).isTrue(); assertThat(stripe.secretKeyEnc()).isEqualTo("ENC-SECRET");
        assertThat(mapper.writeValueAsString(stripe)).contains("pk_test_e8", "acct_e8").doesNotContain("ENC-SECRET", "ENC-WEBHOOK");
        assertThat(stripe.toString()).doesNotContain("ENC-SECRET", "ENC-WEBHOOK");
        var absent = StripeProviderSettings.of(Map.of("MANUAL", Map.of("enabled", true)));
        assertThat(absent.enabled()).isFalse(); assertThat(absent.secretKeyEnc()).isNull();
        assertThat(StripeProviderSettings.of(null).enabled()).isFalse();
        for (String secret : List.of("secretKeyEnc", "webhookSecretEnc")) {
            var annotation = StripeProviderSettings.class.getDeclaredField(secret).getAnnotation(com.agilityhub.core.shared.domain.audit.Sensitive.class);
            assertThat(annotation.value()).as(secret).isEqualTo(com.agilityhub.core.shared.domain.audit.Sensitive.Strategy.HIDE);
        }
    }

    @Test void WP_12_A_WP_13_A_eventsHaveExactlyTheS12AndS13PayloadFieldsAndEnvelope() throws Exception {
        var fixtures = fixture("e8-events");
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("InvoiceIssued", "invoiceId,memberId,period,total,paymentMethodType,kind"); fields.put("InvoiceCollecting", "invoiceId,provider,collectionId");
        fields.put("InvoicePaid", "invoiceId,provider,paidAt"); fields.put("InvoiceFailed", "invoiceId,provider,reason"); fields.put("InvoiceCancelled", "invoiceId,reason");
        fields.put("RemittanceSimulated", "simulationId,period,incidents,totals"); fields.put("RemittanceGenerated", "remittanceId,runId,invoiceIds,fileKey");
        fields.put("RemittanceRolledBack", "remittanceId,runId,invoiceIds"); fields.put("UpfrontPaymentRecorded", "paymentId,memberId,concept,provider,bookingId");
        fields.put("UpfrontPaymentSucceeded", "paymentId,concept,bookingId,packBalanceId,memberId"); fields.put("UpfrontPaymentFailed", "paymentId,bookingId,reason");
        fields.put("PackOpened", "packBalanceId,memberId,dogId,expiresOn"); fields.put("PackConsumed", "packBalanceId,memberId,dogId,bookingId,remaining");
        fields.put("PackRefunded", "packBalanceId,memberId,dogId,bookingId,remaining"); fields.put("PackLowBalance", "packBalanceId,memberId,dogId,remaining");
        fields.put("PackExpiring", "packBalanceId,memberId,dogId,remaining,expiresOn"); fields.put("PackExpired", "packBalanceId,memberId,dogId,remaining,expiresOn");
        fields.put("PackAdjusted", "packBalanceId,memberId,dogId,delta,reason,remaining,expiresOn"); fields.put("StripeWebhookReceived", "eventId,type,outcome");
        fields.put("BillingRunCreated", "runId,period,simulationId,invoiceCount"); fields.put("BillingRunCompleted", "runId,period");
        fields.put("MemberCardInvalidated", "memberId,reason"); fields.put("RemittanceReminderDue", "period,pendingMembers");
        fields.put("InactivityRequested", "periodId,memberId,from,to"); fields.put("InactivityResolved", "periodId,memberId,decision,from,to,fee,cancelledBookings");
        fields.put("InactivityStarted", "periodId,memberId,from,to"); fields.put("InactivityEnded", "periodId,memberId,from,to,finishReason");
        fields.put("InactivityChanged", "periodId,memberId,before,after,cancelledBookings"); fields.put("InactivityCancelled", "periodId,memberId,by,reason");
        fields.put("LeaveRequested", "requestId,memberId,requestedDate,reasonKey"); fields.put("LeaveResolved", "requestId,memberId,decision,source,effectiveDate,cancelledBookings");
        fields.put("LeaveCancelled", "requestId,memberId,by,reason");
        assertThat(fields).hasSize(32);
        assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields.keySet());
        var kinds = new ArrayList<String>();
        for (var kind : BillingEvent.Kind.values()) { kinds.add(kind.name()); }
        for (var kind : CensusLifecycleEvent.Kind.values()) { kinds.add(kind.name()); }
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(fields.keySet());
        Map<String, String> aggregates = Map.of("Invoice", "InvoiceIssued,InvoiceCollecting,InvoicePaid,InvoiceFailed,InvoiceCancelled",
                "Remittance", "RemittanceGenerated,RemittanceRolledBack", "BillingSimulation", "RemittanceSimulated",
                "UpfrontPayment", "UpfrontPaymentRecorded,UpfrontPaymentSucceeded,UpfrontPaymentFailed",
                "PackBalance", "PackOpened,PackConsumed,PackRefunded,PackLowBalance,PackExpiring,PackExpired,PackAdjusted",
                "StripeEvent", "StripeWebhookReceived", "BillingRun", "BillingRunCreated,BillingRunCompleted", "Member", "MemberCardInvalidated", "Club", "RemittanceReminderDue",
                "InactivityPeriod", "InactivityRequested,InactivityResolved,InactivityStarted,InactivityEnded,InactivityChanged,InactivityCancelled");
        for (var entry : fields.entrySet()) {
            String name = entry.getKey();
            assertThat(catalog).as(name + " in the catalog").contains("`" + name);
            assertThat(fixtures.path(name).fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(entry.getValue().split(","));
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(fixtures.path(name), Map.class);
            DomainEvent event = event(name, payload);
            assertThat(event.type()).isEqualTo(name);
            String aggregate = aggregates.entrySet().stream().filter(row -> List.of(row.getValue().split(",")).contains(name)).map(Map.Entry::getKey)
                    .findFirst().orElse("LeaveRequest");
            assertThat(event.aggregateType()).as(name).isEqualTo(aggregate);
            assertThat(event.payload()).isEqualTo(payload);
            assertThat(event.actorAccountId()).isEqualTo("account-a"); assertThat(event.impersonatedMemberId()).isEqualTo("member-a");
            assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
            assertThatThrownBy(() -> event.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
            // The outbox stores the envelope's JSON and hands the consumers the same record back.
            assertThat(mapper.readValue(mapper.writeValueAsString(event), event.getClass())).isEqualTo(event);
        }
        // The proposals of S12 §13 and S13 §13 are the Annex A rows.
        assertThat(row(catalog, "| `BillingRunCreated` ·")).contains("`BillingRunCompleted`", "`PackAdjusted{delta, reason}`", "`MemberCardInvalidated`");
        assertThat(row(catalog, "| `InactivityChanged` ·")).contains("`InactivityCancelled`", "`LeaveCancelled`", "`LeaveResolved{source, decision, cancelledBookings[]}`");
        assertThat(row(catalog, "| `RemittanceReminderDue{")).contains("period, pendingMembers");
    }
    private static DomainEvent event(String name, Map<String, Object> payload) {
        Instant at = Instant.parse("2026-09-24T08:00:00Z");
        for (var kind : BillingEvent.Kind.values()) {
            if (kind.name().equals(name)) { return new BillingEvent(kind, "club-a", "aggregate-a", at, payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE); }
        }
        return new CensusLifecycleEvent(CensusLifecycleEvent.Kind.valueOf(name), "club-a", "aggregate-a", at, payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE);
    }
    private static String row(String catalog, String prefix) {
        return catalog.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(prefix));
    }

    /**
     * Step 6: the notification fixtures of the E8 codes carry exactly the variables of their catalog rows (the row of
     * CATALEG_NOTIFICACIONS plus its closing note, as `NotificationCatalog` encodes it), their event and their action;
     * rendering and sending stay with E7.
     */
    @Test void WP_12_A_WP_13_A_notificationsMatchTheirCatalogRowsAndVariables() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md"));
        var fixtures = fixture("e8-notifications");
        assertThat(fixtures.fieldNames()).toIterable().containsExactly("N-10", "N-11a", "N-11b", "N-14", "N-18a", "N-18b", "N-18c", "N-18d", "N-28", "N-30",
                "N-35", "N-38", "N-41");
        var closing = catalog.lines().filter(line -> line.startsWith("Variants:")).findFirst().orElseThrow();
        fixtures.fields().forEachRemaining(entry -> {
            String code = entry.getKey();
            var spec = NotificationCatalog.byCode(code).orElseThrow();
            String row = catalog.lines().filter(line -> line.startsWith("| " + code + " |")).findFirst().orElseThrow();
            String event = entry.getValue().path("event").asText();
            assertThat(row).as(code).contains("`" + event);
            assertThat(spec.eventTypes()).as(code).contains(event);
            var variables = new ArrayList<String>(); entry.getValue().path("variables").forEach(variable -> variables.add(variable.asText()));
            assertThat(variables).as(code + " = the catalog's variables").containsExactlyElementsOf(spec.variables());
            for (String variable : variables) { assertThat(row + closing).as(code + " " + variable).contains(variable); }
            var action = entry.getValue().path("action");
            if (action.isNull()) { assertThat(row).as(code).containsPattern("\\| — \\| S1[0-9]"); }
            else { assertThat(row).as(code).contains(action.asText()); assertThat(spec.actions().values()).extracting(Enum::name).contains(action.asText()); }
        });
    }

    /** The wire enums are the lists of S12 §3/§5 and S13 §3/§5 (+ the model's `MIGRATED` and S12's `PROVIDER_DISABLED`). */
    @Test void T_12_21_T_13_24_wireEnumsAreTheSpecLists() {
        Map<Class<? extends Enum<?>>, String> expected = new LinkedHashMap<>();
        expected.put(InvoiceStatus.class, "PENDING,COLLECTING,PAID,FAILED,CANCELLED"); expected.put(InvoiceKind.class, "PERIODIC,MANUAL,MIGRATED");
        expected.put(InvoiceLineOrigin.class, "MONTHLY_FEE,MAINTENANCE_FEE,INACTIVITY_FEE,SINGLE_CLASS,PACK,ADJUSTMENT,MIGRATED");
        expected.put(PaymentMethodType.class, "SEPA_DD,CARD,MANUAL"); expected.put(CollectionProvider.class, "SEPA_XML,STRIPE,MANUAL");
        expected.put(CollectionStatus.class, "CREATED,SUBMITTED,SUCCEEDED,FAILED,REFUNDED"); expected.put(RemittanceStatus.class, "GENERATED,SUBMITTED,ROLLED_BACK");
        expected.put(BillingRunStatus.class, "GENERATED,CHARGING,COMPLETED,ROLLED_BACK");
        expected.put(BillingIncidentCode.class, "NO_BANK_ACCOUNT,NO_PLAN,NO_PRICE,CARD_INVALID,CURRENCY_MISMATCH,PROVIDER_DISABLED");
        expected.put(RollbackBlocker.class, "REMITTANCE_SUBMITTED,COLLECTION_SUBMITTED,INVOICE_PAID,MANUAL_INVOICE_AFTER");
        expected.put(UpfrontConcept.class, "ENTRY_FEE,FIRST_MONTH,PACK,SINGLE_CLASS,ACTIVITY,OTHER"); expected.put(UpfrontStatus.class, "DUE,CHECKOUT_PENDING,PARTIAL,PAID,CANCELLED,REFUNDED");
        expected.put(UpfrontProvider.class, "STRIPE,MANUAL"); expected.put(ManualChannel.class, "CASH,TRANSFER,BIZUM");
        expected.put(PackBalanceState.class, "ACTIVE,EXPIRED,CLOSED"); expected.put(PackMovementType.class, "OPEN,CONSUME,REFUND,ADJUST,EXPIRE");
        expected.put(StripeEventOutcome.class, "PROCESSED,IGNORED,FAILED"); expected.put(CheckoutStatus.class, "PENDING,PAID,EXPIRED");
        expected.put(InactivityState.class, "REQUESTED,APPROVED,ACTIVE,FINISHED,DENIED,CANCELLED"); expected.put(LeaveRequestState.class, "PENDING,APPROVED,DENIED,CANCELLED");
        expected.put(LeaveSource.class, "MEMBER,ADMIN,PACK_EXPIRED,MIGRATED"); expected.put(LifecycleOrigin.class, "APP,BACKOFFICE,SYSTEM");
        expected.put(LifecycleDecision.class, "APPROVED,DENIED"); expected.put(LifecycleCanceller.class, "MEMBER,ADMIN,SYSTEM");
        expected.put(InactivityFinishReason.class, "SCHEDULED,ADMIN,LEAVE"); expected.put(InactivityCancelReason.class, "WITHDRAWN,EXPIRED,LEAVE");
        expected.put(LeaveCancelReason.class, "WITHDRAWN,ADMIN,PACK_RENEWED"); expected.put(CancelledBookingType.class, "CLASS,WAITLIST,TRAINING,ACTIVITY");
        expected.put(ChangeSource.class, "MEMBER,ADMIN"); expected.put(OverlapHint.class, "EXTEND");
        expected.put(MemberLeftReason.class, "SIGNUP_REJECTED,LEAVE_REQUEST,ADMIN,PACK_EXPIRED,MIGRATED");
        expected.forEach((type, values) -> {
            assertThat(Arrays.stream(type.getEnumConstants()).map(Enum::name)).as(type.getSimpleName()).containsExactly(values.split(","));
            assertThat(type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).enumAsRef()).as(type.getSimpleName() + " published once").isTrue();
        });
        assertThat(expected).hasSize(31);
    }

    /**
     * Step 8 (S12 §9/§13, S13 §9/§13, A28/A29): every `billing.*`, `inactivity.*` and `leave.*` key of CATALEG_PARAMETRES is in
     * the parameter catalog with its Cànic default, in D11's «Quotes, packs i remesa» block and editable by the club.
     */
    @Test void WP_12_A_WP_13_A_theBillingInactivityAndLeaveParametersCarryTheCanicDefaults() throws Exception {
        var catalog = new ParameterCatalog(mapper);
        var family = catalog.entries().keySet().stream().filter(key -> key.startsWith("billing.") || key.startsWith("inactivity.") || key.startsWith("leave.")).toList();
        var document = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_PARAMETRES.md"));
        var documented = new TreeSet<String>();
        var key = java.util.regex.Pattern.compile("`((?:billing|inactivity|leave)\\.[A-Za-z.]+)`");
        for (String line : document.lines().filter(line -> line.startsWith("| `")).toList()) {
            var cell = line.split("\\|", -1)[1];
            if (line.split("\\|", -1)[2].strip().startsWith("(CLUB")) { continue; }
            var matcher = key.matcher(cell);
            while (matcher.find()) { documented.add(matcher.group(1)); }
        }
        assertThat(new TreeSet<>(family)).isEqualTo(documented);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("billing.cashInvoicing", "SEMESTER"); defaults.put("billing.sepa.collectionDayOfMonth", 1); defaults.put("billing.sepa.useFrst", false);
        defaults.put("billing.sepa.schema", "pain.008.001.02"); defaults.put("billing.invoiceSeriesPattern", "{YYYY}"); defaults.put("billing.invoiceResetYearly", true);
        defaults.put("billing.taxIncluded", true); defaults.put("billing.stripeMaxAttempts", 3); defaults.put("billing.remittanceReminderDay", 22);
        defaults.put("billing.packLowBalanceSessions", 1); defaults.put("billing.packExpiryWarningDays", 14); defaults.put("billing.singleClassCancelPolicy", "REFUND");
        defaults.put("billing.upfrontCutoffDay", 25); defaults.put("billing.packToMemberEntryDiscountPercent", 40); defaults.put("billing.packToMemberMinSessions", 10);
        defaults.put("inactivity.requestDeadlineDay", 25); defaults.put("inactivity.cancelBookingsOnApproval", true); defaults.put("inactivity.maxStartMonthsAhead", 12);
        defaults.put("leave.npsEnabled", true); defaults.put("leave.fullMonthIfLater", true); defaults.put("leave.packExpiryGraceDays", 30);
        defaults.forEach((name, value) -> assertThat(catalog.defaultValue(name)).as(name).isEqualTo(value));
        assertThat(catalog.get("billing.sepa.collectionDayOfMonth").constraints()).containsEntry("min", 0).containsEntry("max", 28);
        assertThat(catalog.get("billing.cashInvoicing").constraints().get("enum")).asList().containsExactly("MONTHLY", "SEMESTER");
        @SuppressWarnings("unchecked") var reasons = (List<Map<String, Object>>) catalog.defaultValue("leave.reasons");
        assertThat(catalog.get("leave.reasons").type()).isEqualTo("json");
        assertThat(reasons).extracting(reason -> reason.get("key")).containsExactly("LEARNED_ENOUGH", "NO_TIME", "NOT_EXPECTED", "EXTERNAL", "OTHER", "CLUB_DECISION", "PACK_EXPIRED");
        assertThat(reasons).allSatisfy(reason -> assertThat(new LinkedHashMap<Object, Object>((Map<?, ?>) reason.get("label"))).containsKeys("ca", "es", "en"));
        for (String name : family) {
            assertThat(catalog.get(name).block()).as(name).isEqualTo("billing");
            assertThat(catalog.get(name).editableBy()).as(name + " editable from D11").isEqualTo("CLUB");
        }
    }
}
