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
