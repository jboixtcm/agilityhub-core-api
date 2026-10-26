package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InputManifestTest {
    static final Path FIXTURE=Path.of("src/test/resources/fixtures/playoff");
    @Test void T_18_16_manifestRecordsActualSourceBytesAndRowsWithoutPrivatePaths() throws Exception {
        var input=PlayoffInput.read(FIXTURE,MappingConfig.load(null));
        var members=input.manifest().files().stream().filter(file->file.name().equals("socis.csv")).findFirst().orElseThrow();
        assertThat(members.sha256()).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(FIXTURE.resolve("socis.csv")))));
        assertThat(members.rows()).isEqualTo(PlayoffTable.read(FIXTURE.resolve("socis.csv")).size()-1);
        assertThat(input.manifest().mappingSha256()).matches("[0-9a-f]{64}");
        assertThat(input.manifest().toString()).doesNotContain(FIXTURE.toString(),"@example.test");
    }
    @Test void T_18_16_sameVersionChangedMappingCannotRecoverAReportFromIdenticalCsv() throws Exception {
        var original=MappingConfig.load(null); var json=new ObjectMapper(); var tree=json.valueToTree(original);
        var columns=tree.path("files").path("members").path("columns");
        for (var column:columns) {
            if (column.path("field").asText().equals("firstName")) { ((com.fasterxml.jackson.databind.node.ObjectNode)column).put("field","nameChanged"); }
        }
        var altered=json.treeToValue(tree,MappingConfig.class); altered.validate();
        var before=PlayoffInput.read(FIXTURE,original); var after=PlayoffInput.read(FIXTURE,altered);
        assertThat(before.manifest().files()).isEqualTo(after.manifest().files());
        assertThat(before.manifest().mappingSha256()).isNotEqualTo(after.manifest().mappingSha256());
        var now=Instant.parse("2026-10-23T10:00:00Z"); var runs=mock(MigrationRunRepository.class); var store=mock(MigrationReportStore.class);
        when(runs.findById("run")).thenReturn(Optional.of(new MigrationRun("run","club","PLAYOFF","APPLY","PRODUCTION",2,"COMPLETED",now,now,Map.of(),before.manifest(),List.of(),null,now.plusSeconds(1),null)));
        var reports=new MigrationReports(runs,store,new MigrationReportWorkbook(),mock(ClubConfigService.class),Clock.fixed(now,ZoneOffset.UTC),mock(com.agilityhub.core.platform.application.audit.AuditWriter.class));
        assertThatThrownBy(()->reports.publish("run",after)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.INPUT_SCHEMA_MISMATCH));
        verifyNoInteractions(store);
    }
}
