package com.agilityhub.core.platform.application.privacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S14 R-14-13 «mai el nom d'un tercer» (T-14-17, E11-T01 round 2): the package's rows keep no name of anybody else, whatever its key. */
class MemberDataPackageTest {
    static Map<String, Object> map(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) { map.put((String) keyValues[i], keyValues[i + 1]); }
        return map;
    }

    @Test void T_14_17_noNameKeyOfAThirdPartyEntersARowWhateverItsKey() {
        var row = MemberDataPackage.row(map("_id", "booking-1", "clubId", "club-a", "version", 3L, "state", "ACTIVE",
                "instructorName", "Staff One", "instructor_name", "Staff Two", "decidedByName", "Staff Three", "displayName", "Staff Four",
                "bookedBy", map("accountId", "staff-account", "role", "INSTRUCTOR", "name", "Staff Five", "fullName", "Staff Six", "email", "staff@example.test"),
                "remarksMeta", map("updatedByName", "Staff Seven", "updatedAt", "2026-07-01"),
                "history", List.of(map("markedBy", map("accountId", "staff-account", "displayName", "Staff Eight")))));
        assertThat(row.toString()).doesNotContain("Staff", "staff@example.test", "club-a");
        assertThat(row).containsEntry("id", "booking-1").containsEntry("state", "ACTIVE").doesNotContainKeys("clubId", "version");
        assertThat(row.get("bookedBy")).isEqualTo(map("accountId", "staff-account", "role", "INSTRUCTOR"));
    }

    @Test void T_14_17_theMembersOwnNamesAndTheNamesOfThingsStay() {
        var row = MemberDataPackage.row(map("firstName", "Laura", "memberSnapshot", map("fullName", "Laura Example"), "name", "Duna",
                "handlerName", "Laura Example", "paymentMethod", map("holderName", "Laura Example"), "dogName", "Duna",
                "files", List.of(map("name", "cartilla.pdf", "fileName", "cartilla.pdf")), "levelName", "Grau 1", "ringName", "Pista 1"));
        assertThat(row).containsEntry("firstName", "Laura").containsEntry("name", "Duna").containsEntry("handlerName", "Laura Example")
                .containsEntry("levelName", "Grau 1").containsEntry("ringName", "Pista 1").containsEntry("dogName", "Duna");
        assertThat(row.toString()).contains("fullName=Laura Example", "holderName=Laura Example", "name=cartilla.pdf", "fileName=cartilla.pdf");
    }

    /**
     * R-14-13 `familyGroup` «sense dades d'altres membres» (E11-T11): S04's `familyGroupClaim` names the group's holder and the
     * holder's dog, another member's data; only its status and the holder's id stay.
     */
    @Test void T_14_17_theFamilyGroupClaimKeepsNoNameOfTheHolder() {
        var row = MemberDataPackage.row(map("firstName", "Laura", "familyGroupClaim", map("status", "FOUND", "holderName", "Holder Example",
                "dogName", "Holder Dog", "holderMemberId", "member-holder", "leavePending", false)));
        assertThat(row.get("familyGroupClaim")).isEqualTo(map("status", "FOUND", "holderMemberId", "member-holder"));
        assertThat(row.toString()).doesNotContain("Holder Example", "Holder Dog");
    }

    /** The fail-closed filter (E11-T11): a plural `…Names` key is a name key, and an actor stays an actor however deep its maps go. */
    @Test void T_14_17_pluralNameKeysAndMapsNestedInAnActorKeepNoName() {
        var row = MemberDataPackage.row(map("instructorNames", List.of("Staff One", "Staff Two"), "helper_names", "Staff Three",
                "cancelledBy", map("accountId", "staff-account", "profile", map("name", "Staff Four", "contact", map("email", "staff@example.test", "role", "ADMIN")),
                        "history", List.of(map("name", "Staff Five", "at", "2026-07-01")))));
        assertThat(row.toString()).doesNotContain("Staff", "staff@example.test");
        assertThat(row).doesNotContainKeys("instructorNames", "helper_names");
        assertThat(row.get("cancelledBy")).isEqualTo(map("accountId", "staff-account", "profile", map("contact", map("role", "ADMIN")),
                "history", List.of(map("at", "2026-07-01"))));
    }
}
