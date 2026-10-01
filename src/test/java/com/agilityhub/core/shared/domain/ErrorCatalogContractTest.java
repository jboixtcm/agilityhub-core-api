package com.agilityhub.core.shared.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ErrorCatalogContractTest {
    @Test void E0_T04_errorCatalogMatchesEveryConcreteCodeAndCanonicalStatus() throws Exception {
        var codes = new LinkedHashSet<String>();
        var pattern = Pattern.compile("`([A-Z][A-Z_]+)`");
        for (String row : Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"))) {
            if (!row.startsWith("|") && !row.startsWith("Transversals:")) { continue; }
            var matcher = pattern.matcher(row);
            var status = Pattern.compile("^\\| (\\d{3}) \\|").matcher(row);
            Integer expectedStatus = status.find() ? Integer.valueOf(status.group(1)) : null;
            while (matcher.find()) {
                codes.add(matcher.group(1));
                if (expectedStatus != null) {
                    assertThat(ErrorCode.valueOf(matcher.group(1)).httpStatus()).isEqualTo(expectedStatus);
                }
            }
        }
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).containsExactlyInAnyOrderElementsOf(codes);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.httpStatus()).isEqualTo(405);
        assertThat(ErrorCode.NOT_ACCEPTABLE.httpStatus()).isEqualTo(406);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.httpStatus()).isEqualTo(415);
        for (ErrorCode code : ErrorCode.values()) {
            var exception = new ApiException(code);
            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(code.name());
            assertThat(exception.details()).isEmpty();
            assertThat(code.httpStatus()).isBetween(400, 501);
        }
    }
    @Test void T_08_47_T_09_24_T_15_29_bookingTrainingAndJobErrorsFollowTheCatalogStatuses() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (String names : java.util.List.of(
                // Explicit in §1.
                "409:CLASS_FULL,SEAT_TAKEN,SEAT_HOLD_EXPIRED,SLOT_TAKEN,BOOKING_LIMIT_REACHED,WAITLIST_LIMIT,TRAINING_LIMIT_REACHED,JOB_ALREADY_RUNNING,STALE_VERSION,INVALID_STATE,IDEMPOTENCY_KEY_REUSED",
                "422:NOT_YET_OPEN,CLASS_NOT_BOOKABLE,LEVEL_NOT_ALLOWED,PACK_EMPTY,MEMBER_NOT_ACTIVE,BOOKING_BLOCKED,INACTIVITY_PERIOD,DOG_NOT_ALLOWED,SLOT_OUT_OF_WINDOW,CLUB_CLOSED,RING_NOT_RESERVABLE",
                "400:VALIDATION_ERROR,INVALID_FILTER,INVALID_TIME_RANGE", "404:MODULE_DISABLED,NOT_FOUND,DOG_NOT_ACCESSIBLE",
                // Explicit in §1 since E5-T09 (organizer ruling 2026-09-24, the statuses S09/S15 §6 already show).
                "400:SLOT_NOT_ON_GRID", "403:OVERRIDE_NOT_ALLOWED", "404:JOB_UNKNOWN",
                // Rule 0: ALREADY_ prefix and _CONFLICT suffix.
                "409:ALREADY_BOOKED,ALREADY_ON_WAITLIST,RING_BLOCK_CONFLICT,CLASS_CONFLICT",
                // Rule 0: everything else is 422, whatever S08/S09/S15 §6 write (DOG_ALREADY_BOOKED 409, RING_HAS_BOOKINGS 409).
                "422:CLASS_NOT_FULL,WAITLIST_FULL,WAITLIST_NOT_NOTIFIED,WAITLIST_OFFER_EXPIRED,WAITLIST_ENTRY_NOT_LIVE,SWAP_NOT_ALLOWED,BOOKING_NOT_CANCELLABLE,WEEKLY_LIMIT_DONE,DOG_ALREADY_BOOKED,TRAINING_CANCEL_TOO_LATE,RING_HAS_BOOKINGS,RING_BLOCK_MANAGED_BY_ACTIVITY,NOT_DUE")) {
            String[] pair = names.split(":");
            for (String name : pair[1].split(",")) { expected.put(name, Integer.parseInt(pair[0])); }
        }
        assertThat(expected).hasSize(48);
        expected.forEach((name, status) -> {
            assertThat(catalog).contains("`" + name + "`");
            assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);
        });
        for (String locale : java.util.List.of("ca", "es", "en")) {
            var messages = new java.util.Properties();
            try (var input = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(input); }
            expected.keySet().forEach(name -> assertThat(messages.getProperty("error." + name)).as(locale + ":" + name).isNotBlank());
        }
    }
    @Test void T_10_21_attendanceAndFollowupErrorsFollowTheLiteralCatalogStatusRule() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        String row = catalog.lines().filter(line -> line.startsWith("| S10 |")).findFirst().orElseThrow();
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (String names : java.util.List.of(
                // Rule 0 (literal, beats S10 §6 which writes 409 for seven of them): no ALREADY_ prefix at the start, no 409 suffix → 422.
                "422:ATTENDANCE_NOT_OPEN,ATTENDANCE_WINDOW_CLOSED,ATTENDANCE_NOTIFIED_FINAL,ATTENDANCE_BOOKING_NOT_ACTIVE,INSTRUCTOR_NOTICE_DISABLED,TASK_ALREADY_DONE,TASK_NOT_DONE,ATTACHMENT_LIMIT_REACHED,ATTACHMENT_ENTITY_MISMATCH,DOG_NOT_ACTIVE,BOOKING_NOT_CANCELLABLE",
                // Explicit in §1.
                "409:STALE_VERSION,INVALID_STATE,IDEMPOTENCY_KEY_REUSED", "404:DOG_NOT_ACCESSIBLE,MODULE_DISABLED,NOT_FOUND",
                "400:FILE_TOO_LARGE,FILE_TYPE_NOT_ALLOWED,VALIDATION_ERROR,INVALID_FILTER", "403:FORBIDDEN,IMPERSONATION_DENIED")) {
            String[] pair = names.split(":");
            for (String name : pair[1].split(",")) { expected.put(name, Integer.parseInt(pair[0])); }
        }
        assertThat(expected).hasSize(23);
        expected.forEach((name, status) -> {
            assertThat(catalog).contains("`" + name + "`");
            assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);
        });
        // The S10 row of §2 lists exactly the eleven codes of the vertical.
        var own = new java.util.ArrayList<String>();
        var matcher = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(row);
        while (matcher.find()) { own.add(matcher.group(1)); }
        assertThat(own).containsExactlyElementsOf(java.util.List.of(expected.keySet().toArray(new String[0])).subList(0, 11));
        for (String locale : java.util.List.of("ca", "es", "en")) {
            var messages = new java.util.Properties();
            try (var input = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(input); }
            expected.keySet().forEach(name -> assertThat(messages.getProperty("error." + name)).as(locale + ":" + name).isNotBlank());
        }
    }
    /**
     * E7-T01 step 6: every S11 §6 code with CATALEG_ERRORS' status (rule 0 is literal and beats S11 §6, which writes 400 for
     * INVALID_REMINDER_OPTION, PUSH_SUBSCRIPTION_INVALID, UNSUBSCRIBE_TOKEN_INVALID, CHANNEL_NOT_ALLOWED and 409 for four TEMPLATE_*
     * codes). S11's TEMPLATE_MISSING_VARIABLE is in neither: VALIDATION_ERROR + details.missingVariables meanwhile (proposal).
     */
    @Test void T_11_16_T_11_21_messagingErrorsFollowTheLiteralCatalogStatusRule() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (String names : java.util.List.of(
                "422:CHANNEL_NOT_ALLOWED,INVALID_REMINDER_OPTION,NO_RECIPIENTS,PUSH_SUBSCRIPTION_INVALID,TEMPLATE_MANDATORY,TEMPLATE_NOT_CATALOG,TEMPLATE_NOT_CUSTOM,TEMPLATE_NOT_SENDABLE,UNSUBSCRIBE_TOKEN_INVALID",
                "400:SMS_BODY_REQUIRED,SMS_BODY_TOO_LONG,TEMPLATE_SYNTAX_ERROR,TEMPLATE_UNKNOWN_VARIABLE,VALIDATION_ERROR,INVALID_FILTER", "401:WEBHOOK_SIGNATURE_INVALID",
                "404:NOT_FOUND,MODULE_DISABLED", "409:STALE_VERSION,INVALID_STATE,IDEMPOTENCY_KEY_REUSED")) {
            String[] pair = names.split(":");
            for (String name : pair[1].split(",")) { expected.put(name, Integer.parseInt(pair[0])); }
        }
        assertThat(expected).hasSize(21);
        expected.forEach((name, status) -> {
            assertThat(catalog).contains("`" + name + "`");
            assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);
        });
        // The S11 row of §2 lists exactly the vertical's fourteen own codes.
        String row = catalog.lines().filter(line -> line.startsWith("| S11 |")).findFirst().orElseThrow();
        var own = new java.util.TreeSet<String>();
        var matcher = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(row);
        while (matcher.find()) { own.add(matcher.group(1)); }
        assertThat(own).containsExactlyInAnyOrder("CHANNEL_NOT_ALLOWED", "INVALID_REMINDER_OPTION", "NO_RECIPIENTS", "PUSH_SUBSCRIPTION_INVALID", "SMS_BODY_REQUIRED",
                "SMS_BODY_TOO_LONG", "TEMPLATE_MANDATORY", "TEMPLATE_NOT_CATALOG", "TEMPLATE_NOT_CUSTOM", "TEMPLATE_NOT_SENDABLE", "TEMPLATE_SYNTAX_ERROR",
                "TEMPLATE_UNKNOWN_VARIABLE", "UNSUBSCRIBE_TOKEN_INVALID", "WEBHOOK_SIGNATURE_INVALID");
        assertThat(catalog).doesNotContain("TEMPLATE_MISSING_VARIABLE");
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).doesNotContain("TEMPLATE_MISSING_VARIABLE");
        for (String locale : java.util.List.of("ca", "es", "en")) {
            var messages = new java.util.Properties();
            try (var input = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(input); }
            expected.keySet().forEach(name -> assertThat(messages.getProperty("error." + name)).as(locale + ":" + name).isNotBlank());
        }
    }
    /**
     * E8-T01 step 4 (CATALEG_ERRORS §1 + §3 rule 0; the catalog wins over S12 §6 and S13 §6): every S12/S13 code with its
     * canonical status, a message in the three languages, and the §2 rows of the two specs naming exactly the vertical's codes.
     * The state conflicts of S13 are explicit 409s in §1 since 26-09 (ruling E50); rule 0 makes the rest 422 (S12 §6 writes 409 for
     * REFUND_EXCEEDS_PAID; S13 §6 writes 400 for LEAVE_REASON_UNKNOWN and 409 for MEMBER_NOT_LEFT and NO_PLANNED_LEAVE). No status
     * changed: the enum already had every one (E0-T04, E50).
     */
    @Test void T_12_21_T_13_24_billingInactivityAndLeaveErrorsFollowTheCatalogStatuses() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (String names : java.util.List.of(
                // Explicit in §1.
                "409:BILLING_BUSY,RUN_EXISTS,SIMULATION_STALE,RUN_NOT_ROLLBACKABLE,MAX_ATTEMPTS,INVALID_STATE,STALE_VERSION,IDEMPOTENCY_KEY_REUSED",
                "422:COLLECTION_DATE_TOO_SOON,CURRENCY_MISMATCH,PAYMENT_PROVIDER_NOT_ENABLED,MEMBER_NOT_ACTIVE,INACTIVITY_PERIOD",
                "401:WEBHOOK_SIGNATURE_INVALID", "403:READ_ONLY,IMPERSONATION_DENIED,FORBIDDEN", "404:MODULE_DISABLED,NOT_FOUND",
                "400:VALIDATION_ERROR,INVALID_FILTER", "405:METHOD_NOT_ALLOWED",
                // Explicit in §1 since 26-09 (E50): the state conflicts.
                "409:INACTIVITY_INVALID_STATE,LEAVE_ALREADY_REQUESTED,LEAVE_ALREADY_SCHEDULED,LEAVE_INVALID_STATE,CLUB_NOT_EMPTY",
                // Rule 0: the _OVERLAP suffix.
                "409:INACTIVITY_OVERLAP",
                // Rule 0: everything else is 422.
                "422:NO_INVOICES,SEPA_NOT_CONFIGURED,NO_PAYMENT_METHOD,AMOUNT_EXCEEDS_DUE,PLAN_NOT_PACK,PACK_NEGATIVE,REFUND_EXCEEDS_PAID,INACTIVITY_DEADLINE_PASSED,"
                        + "INACTIVITY_INVALID_RANGE,INACTIVITY_NOT_APPLICABLE,LEAVE_DATE_INVALID,LEAVE_REASON_UNKNOWN,MEMBER_NOT_LEFT,NO_PLANNED_LEAVE,MEMBER_LEAVING")) {
            String[] pair = names.split(":");
            for (String name : pair[1].split(",")) { expected.put(name, Integer.parseInt(pair[0])); }
        }
        assertThat(expected).hasSize(43);
        expected.forEach((name, status) -> {
            assertThat(catalog).contains("`" + name + "`");
            assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);
        });
        var s12 = new java.util.TreeSet<String>(); var s13 = new java.util.TreeSet<String>();
        for (var entry : java.util.Map.of("| S12 |", s12, "| S13 |", s13).entrySet()) {
            String row = catalog.lines().filter(line -> line.startsWith(entry.getKey())).findFirst().orElseThrow();
            var matcher = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(row);
            while (matcher.find()) { entry.getValue().add(matcher.group(1)); }
        }
        assertThat(s12).containsExactlyInAnyOrder("BILLING_BUSY", "RUN_EXISTS", "SIMULATION_STALE", "RUN_NOT_ROLLBACKABLE", "COLLECTION_DATE_TOO_SOON", "NO_INVOICES",
                "SEPA_NOT_CONFIGURED", "MAX_ATTEMPTS", "NO_PAYMENT_METHOD", "REFUND_EXCEEDS_PAID", "AMOUNT_EXCEEDS_DUE", "PLAN_NOT_PACK", "PACK_NEGATIVE",
                "CURRENCY_MISMATCH", "PAYMENT_PROVIDER_NOT_ENABLED", "WEBHOOK_SIGNATURE_INVALID");
        // S13 R-13-02's INACTIVITY_NOT_APPLICABLE is in the §2 row (since 19-09) and in the enum, with its three messages.
        assertThat(s13).containsExactlyInAnyOrder("INACTIVITY_NOT_APPLICABLE", "INACTIVITY_DEADLINE_PASSED", "INACTIVITY_INVALID_RANGE", "INACTIVITY_INVALID_STATE",
                "INACTIVITY_OVERLAP", "LEAVE_ALREADY_REQUESTED", "LEAVE_ALREADY_SCHEDULED", "LEAVE_DATE_INVALID", "LEAVE_INVALID_STATE", "LEAVE_REASON_UNKNOWN",
                "MEMBER_LEAVING", "MEMBER_NOT_LEFT", "NO_PLANNED_LEAVE", "READ_ONLY");
        for (String locale : java.util.List.of("ca", "es", "en")) {
            var messages = new java.util.Properties();
            try (var input = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(input); }
            expected.keySet().forEach(name -> assertThat(messages.getProperty("error." + name)).as(locale + ":" + name).isNotBlank());
        }
    }
    @Test void T_06_20_T_07_17_schedulingAndActivityErrorsFollowTheLiteralCatalogStatusRule() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (String names : java.util.List.of(
                "400:INVALID_TIME_RANGE,INVALID_SLOT_GRANULARITY,VALIDATION_ERROR,INVALID_FILTER,FILE_TOO_LARGE,FILE_TYPE_NOT_ALLOWED,FILE_NOT_FOUND",
                "403:INVALID_API_KEY", "404:MODULE_DISABLED,NOT_FOUND,DOG_NOT_ACCESSIBLE",
                "409:STALE_VERSION,INVALID_STATE,IDEMPOTENCY_KEY_REUSED,ACTIVITY_FULL,WEEK_ALREADY_GENERATED,BAND_NOT_EMPTY,DUPLICATE_NAME,DUPLICATE_SLUG,BAND_OVERLAP,RING_BLOCK_CONFLICT,ALREADY_REGISTERED,SLUG_LOCKED",
                "422:LEVEL_REQUIRED,LEVELS_DISABLED,LEVEL_NOT_ALLOWED,MEMBER_NOT_ACTIVE,BOOKING_BLOCKED,INACTIVITY_PERIOD,OUTSIDE_OPENING_HOURS,TOO_MANY_INSTRUCTORS,DESCRIPTION_REQUIRED,TEMPLATE_KIND_MISMATCH,TEMPLATE_INCONSISTENT,WEEK_IN_PAST,WEEK_INCONSISTENT,NOTHING_TO_VALIDATE,CAPACITY_BELOW_BOOKINGS,ADMIN_TEXT_REQUIRED,RING_BLOCKED,RING_BLOCK_MANAGED_BY_ACTIVITY,RING_HAS_BOOKINGS,REGISTRATION_CLOSED,ACTIVITY_NOT_PUBLISHED,ACTIVITY_INCOMPLETE,ACTIVITY_IN_PAST,ACTIVITY_HAS_REGISTRATIONS,REGISTRATION_NOT_CANCELLABLE,CAPACITY_BELOW_REGISTRATIONS,TOO_MANY_DOCUMENTS,LOCALE_NOT_ENABLED",
                "429:RATE_LIMITED")) {
            String[] pair = names.split(":");
            for (String name : pair[1].split(",")) { expected.put(name, Integer.parseInt(pair[0])); }
        }
        expected.forEach((name, status) -> {
            assertThat(catalog).contains("`" + name + "`");
            assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);
        });
        for (String locale : java.util.List.of("ca", "es", "en")) {
            var messages = new java.util.Properties();
            try (var input = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(input); }
            expected.keySet().forEach(name -> assertThat(messages.getProperty("error." + name)).as(locale + ":" + name).isNotBlank());
        }
    }

}
