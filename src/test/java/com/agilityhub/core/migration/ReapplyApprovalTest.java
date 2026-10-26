package com.agilityhub.core.migration;

import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ReapplyApprovalTest {
    @TempDir Path temporary;
    static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");
    static final LocalDate CUTOVER=LocalDate.parse("2026-10-30");
    final InputManifest manifest=new InputManifest("a".repeat(64),List.of(new InputManifest.File("members.csv","b".repeat(64),2)));
    String json() { return """
        {"version":1,"clubId":"fictional-club","priorRunId":"prior-run","inputManifestSha256":"%s",
         "cutover":"2026-10-30","steps":[1,2,3],"approvedBy":"Fictional operator","approvedAt":"2026-10-23T12:00:00Z",
         "reason":"Correct fictional mapped data","runbookReference":"fictional-runbook-entry-1"}
        """.formatted(ReapplyApproval.manifestSha256(manifest)); }
    Path approval(Path directory,String contents) throws Exception {
        Files.createDirectories(directory); var file=directory.resolve("approval.json"); Files.writeString(file,contents);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); return file;
    }
    void refused(Path file) {
        assertThatThrownBy(()->ReapplyApproval.read(file)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
    }
    @Test void T_18_14_privateWrittenApprovalBindsEveryCandidateAndPriorRunField() throws Exception {
        var approved=ReapplyApproval.read(approval(temporary,json())); var selected=MigrationSteps.parse("1-3");
        assertThat(approved.digest()).matches("[a-f0-9]{64}");
        assertThat(approved.matches("fictional-club","prior-run",manifest,CUTOVER,selected,NOW,NOW.minusSeconds(1))).isTrue();
        assertThat(approved.matches("other-club","prior-run",manifest,CUTOVER,selected,NOW,NOW)).isFalse();
        assertThat(approved.matches("fictional-club","new-prior",manifest,CUTOVER,selected,NOW,NOW)).isFalse();
        assertThat(approved.matches("fictional-club","prior-run",manifest,CUTOVER.plusDays(1),selected,NOW,NOW)).isFalse();
        assertThat(approved.matches("fictional-club","prior-run",manifest,CUTOVER,MigrationSteps.all(),NOW,NOW)).isFalse();
        assertThat(approved.matches("fictional-club","prior-run",manifest,CUTOVER,selected,NOW.minusSeconds(1),NOW)).isFalse();
        assertThat(approved.matches("fictional-club","prior-run",manifest,CUTOVER,selected,NOW,NOW.plusSeconds(1))).isFalse();
        var changed=new InputManifest("c".repeat(64),manifest.files());
        assertThat(approved.matches("fictional-club","prior-run",changed,CUTOVER,selected,NOW,NOW)).isFalse();
    }
    @Test void T_18_14_manifestDigestIsOrderIndependentButBindsRowsFilesAndMapping() {
        var second=new InputManifest.File("packs.csv","c".repeat(64),3);
        var first=new InputManifest(manifest.mappingSha256(),List.of(manifest.files().getFirst(),second));
        var reordered=new InputManifest(manifest.mappingSha256(),List.of(second,manifest.files().getFirst()));
        assertThat(ReapplyApproval.manifestSha256(first)).isEqualTo(ReapplyApproval.manifestSha256(reordered));
        assertThat(ReapplyApproval.manifestSha256(first)).isNotEqualTo(ReapplyApproval.manifestSha256(manifest));
        assertThat(ReapplyApproval.manifestSha256(manifest)).isNotEqualTo(ReapplyApproval.manifestSha256(
                new InputManifest(manifest.mappingSha256(),List.of(new InputManifest.File("members.csv","b".repeat(64),3)))));
    }
    @Test void T_18_14_approvalRefusesRepositorySyncSymlinkAndPublicPermissions() throws Exception {
        var git=temporary.resolve("git"); Files.createDirectories(git.resolve(".git")); refused(approval(git,json()));
        refused(approval(temporary.resolve("Dropbox-fixture"),json()));
        var file=approval(temporary.resolve("private"),json()); var link=temporary.resolve("alias.json"); Files.createSymbolicLink(link,file); refused(link);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r-----")); refused(file);
    }
    @Test void T_18_14_approvalRefusesUnknownDuplicateMissingAndOversizedContent() throws Exception {
        for(var invalid:List.of(json().replace("\"version\":1","\"version\":1,\"version\":1"),
                json().replace("\"version\":1","\"version\":4294967297"),json().replace("\"version\":1","\"unexpected\":1"),
                json().replace("[1,2,3]","[1,2,2]"),json().replace("Fictional operator",""),
                json().replace("2026-10-30","invalid-date"),json().replace("2026-10-23T12:00:00Z","invalid-instant"),
                json()+"{}","x".repeat(16_385),""," ","null","[]")) {
            refused(approval(temporary,invalid));
        }
    }
}
