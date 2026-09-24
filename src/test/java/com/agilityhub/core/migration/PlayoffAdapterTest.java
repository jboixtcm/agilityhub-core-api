package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayoffAdapterTest {
    @TempDir Path temp;
    final MappingConfig mapping=MappingConfig.load(null);
    final String key=UUID.randomUUID().toString();
    Path fixture() { return Path.of("src/test/resources/fixtures/playoff"); }
    Path copy() throws Exception {
        var dir=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        for (var schema:mapping.files().values()) { Files.copy(fixture().resolve(schema.name()),dir.resolve(schema.name())); }
        return dir;
    }
    @Test void T_18_03_anonymizerRemovesSyntheticPersonalValuesAndPreservesFiftyRowRelations() throws Exception {
        var source=copy(); var table=new ArrayList<>(PlayoffTable.read(source.resolve("socis.csv")).subList(0,51));
        PlayoffTable.write(source.resolve("socis.csv"),table);
        var first=temp.resolve("one"); var second=temp.resolve("two");
        var anonymizer=new PlayoffAnonymizer(key);
        anonymizer.anonymize(source,first,mapping); anonymizer.anonymize(source,second,mapping);
        for (var schema:mapping.files().values()) {
            var before=PlayoffTable.read(source.resolve(schema.name())); var after=PlayoffTable.read(first.resolve(schema.name()));
            assertThat(after).hasSameSizeAs(before); assertThat(after.getFirst()).isEqualTo(before.getFirst());
            assertThat(Files.readAllBytes(first.resolve(schema.name()))).isEqualTo(Files.readAllBytes(second.resolve(schema.name())));
            for (int r=1;r<before.size();r++) {
                assertThat(after.get(r)).hasSameSizeAs(before.get(r));
                for (var column:schema.columns()) {
                    String original=before.get(r).get(column.at()-1), replacement=after.get(r).get(column.at()-1);
                    if (column.anonymize().equals("keep") || original.isBlank()) { assertThat(replacement).isEqualTo(original); }
                    else if (!Set.of("redact","postal").contains(column.anonymize()) && !original.equals("INVALID_TEST_IBAN")) { assertThat(replacement).isNotEqualTo(original); }
                    if (column.anonymize().equals("email") && !original.isBlank()) { assertThat(replacement).endsWith("@example.test"); }
                    if (column.anonymize().equals("iban") && PlayoffAnonymizer.validIban(original)) { assertThat(PlayoffAnonymizer.validIban(replacement)).isTrue(); }
                    if (column.anonymize().equals("document") && !original.isBlank()) {
                        int number=Integer.parseInt(replacement.substring(0,8)); assertThat(replacement.charAt(8)).isEqualTo("TRWAGMYFPDXBNJZSQVHLCKE".charAt(number%23));
                    }
                }
            }
        }
        var before=PlayoffInput.read(source,mapping); var after=PlayoffInput.read(first,mapping);
        assertThat(after.files().get("members").getFirst().get("id")).isEqualTo(after.files().get("plans").getFirst().get("id"));
        for (String file:List.of("groups","team")) {
            for (int i=0;i<before.files().get(file).size();i++) {
                var row=before.files().get(file).get(i); var transformed=after.files().get(file).get(i);
                assertThat(transformed.get(file.equals("team") ? "number" : "id")).isEqualTo(anonymizer.replace(file.equals("team") ? "number" : "id",row.get(file.equals("team") ? "number" : "id")));
            }
        }
        // persones.csv (R-18-04 (f)) keeps its joins: the same HMAC ids and documents as the members file.
        var persons=before.files().get("persons"); assertThat(persons).isNotEmpty();
        for (int i=0;i<persons.size();i++) {
            var row=persons.get(i); var transformed=after.files().get("persons").get(i);
            assertThat(transformed.get("principalId")).isEqualTo(anonymizer.replace("id",row.get("principalId")));
            assertThat(transformed.get("joinedId")).isEqualTo(anonymizer.replace("id",row.get("joinedId")));
            assertThat(transformed.get("document")).isEqualTo(anonymizer.replace("document",row.get("document")));
        }
        var members=PlayoffInput.read(fixture(),mapping).files().get("members");
        var principal=members.stream().filter(m -> m.get("id").equals(persons.getFirst().get("principalId"))).findFirst().orElseThrow();
        assertThat(anonymizer.replace("id",principal.get("id"))).isEqualTo(after.files().get("persons").getFirst().get("principalId"));
        assertThat(anonymizer.replace("document","12.345.678-Z")).isEqualTo(anonymizer.replace("document","12345678Z"));
        assertThat(anonymizer.replace("surname","Example (Pup)")).contains(anonymizer.replace("dog","Pup"));
        assertThat(anonymizer.replace("iban","invalid")).isEqualTo("INVALID_TEST_IBAN");
        assertThat(anonymizer.replace("passport","AB123")).startsWith("TEST");
    }
    /** The mapped codes that the Cànic seed (seeds/club-canic.yaml) does not define, per catalog. */
    static Map<String,Set<String>> missingSeedCodes(MappingConfig mapping) throws Exception {
        var seed=new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(Path.of("seeds/club-canic.yaml").toFile());
        var result=new TreeMap<String,Set<String>>();
        for (var catalog:Map.of("plans",mapping.plans().values(),"levels",mapping.levels().values()).entrySet()) {
            var codes=new HashSet<String>(); seed.at("/catalogs/"+catalog.getKey()).forEach(item -> codes.add(item.path("code").asText()));
            assertThat(codes).as(catalog.getKey()).isNotEmpty();
            var missing=new TreeSet<>(catalog.getValue()); missing.removeAll(codes); result.put(catalog.getKey(),missing);
        }
        return result;
    }
    @Test void T_18_01_everyMappedPlanAndLevelCodeExistsInTheCanicSeed() throws Exception {
        assertThat(missingSeedCodes(mapping)).isEqualTo(Map.of("plans",Set.of(),"levels",Set.of()));
        // The check catches a mismatch such as the v1 `cadells: CADELLS` (the seed code is CAD).
        var levels=new LinkedHashMap<>(mapping.levels()); levels.put("cadells","CADELLS");
        var plans=new LinkedHashMap<>(mapping.plans()); plans.put("instructors","INSTRUCTOR_FREE");
        var broken=new MappingConfig(mapping.version(),mapping.defaultClub(),mapping.ageWarningYears(),mapping.suspectBirthYears(),mapping.inferredDogPrefix(),mapping.statuses(),plans,
                mapping.unresolvedPlans(),mapping.familyPlans(),mapping.instructorPlans(),levels,mapping.levelWarnings(),mapping.levelFlags(),mapping.unresolvedLevels(),mapping.photoOwner(),mapping.files());
        assertThat(missingSeedCodes(broken)).isEqualTo(Map.of("plans",Set.of("INSTRUCTOR_FREE"),"levels",Set.of("CADELLS")));
        assertThat(mapping.version()).isEqualTo(2); assertThat(mapping.levelWarnings()).containsEntry("pendent","LEVEL_PENDING");
        assertThat(mapping.plans()).containsEntry("familiar abonat/curs","ABONAT_FAMILIAR"); assertThat(mapping.familyPlans()).contains("familiar abonat/curs");
        assertThat(mapping.unresolvedPlans()).contains("quota reduïda"); assertThat(mapping.unresolvedLevels()).isEmpty();
        for (var invalid:List.of(new MappingConfig(2,"canic",16,10,"Gos de ",mapping.statuses(),plans,Set.of(),Set.of(),Set.of(),levels,Map.of("unknown","LEVEL_PENDING"),Map.of(),Set.of(),"DOG",mapping.files()),
                new MappingConfig(2,"canic",16,10,"Gos de ",mapping.statuses(),plans,Set.of(),Set.of(),Set.of(),levels,Map.of("pendent","OTHER"),Map.of(),Set.of(),"DOG",mapping.files()),
                new MappingConfig(2,"canic",16,10,"Gos de ",mapping.statuses(),plans,Set.of(),Set.of(),Set.of(),levels,Map.of(),Map.of(),Set.of(),"MEMBER",mapping.files()),
                new MappingConfig(2,"canic",16,10,"Gos de ",mapping.statuses(),plans,Set.of(),Set.of(),Set.of(),levels,null,Map.of(),Set.of(),"DOG",mapping.files()))) {
            assertThatThrownBy(invalid::validate).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_18_03_anonymizerRejectsUnsafeOrInvalidInputsWithoutOverwriting() throws Exception {
        assertThatThrownBy(() -> new PlayoffAnonymizer(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new PlayoffAnonymizer("short")).isInstanceOf(ApiException.class);
        var dir=copy(); var anonymizer=new PlayoffAnonymizer(key);
        assertThatThrownBy(() -> anonymizer.anonymize(dir,dir,mapping)).isInstanceOf(ApiException.class);
        var alias=temp.resolve("alias"); Files.createSymbolicLink(alias,dir);
        assertThatThrownBy(() -> anonymizer.anonymize(dir,alias,mapping)).isInstanceOf(ApiException.class);
        var output=temp.resolve("output"); anonymizer.anonymize(dir,output,mapping);
        assertThatThrownBy(() -> anonymizer.anonymize(dir,output,mapping)).isInstanceOf(ApiException.class);
        Files.delete(dir.resolve("socis.csv"));
        assertThatThrownBy(() -> anonymizer.anonymize(dir,temp.resolve("missing"),mapping)).isInstanceOf(ApiException.class);
        assertThat(Files.exists(temp.resolve("missing"))).isFalse();
    }
    @Test void T_18_01_csvAndXlsxPreserveDuplicateHeadingsDatesAndQuotedCells() throws Exception {
        var rows=List.of(List.of("Data naixement","Data naixement","note"),List.of("1980-01-01","2020-03-04","a,\"b\"\nline"));
        for (String extension:List.of("csv","xlsx")) {
            var file=temp.resolve("input."+extension); PlayoffTable.write(file,rows); assertThat(PlayoffTable.read(file)).isEqualTo(rows);
        }
        assertThat(PlayoffTable.csv("\uFEFFa;b\r\n1;2")).isEqualTo(List.of(List.of("a","b"),List.of("1","2")));
        assertThat(PlayoffTable.csv("a,b\n\"\",x\r")).isEqualTo(List.of(List.of("a","b"),List.of("","x")));
        for (String invalid:List.of("\"open","a\"b","\"a\"x")) { assertThatThrownBy(() -> PlayoffTable.csv(invalid)).isInstanceOf(ApiException.class); }
        assertThatThrownBy(() -> PlayoffTable.read(temp.resolve("absent.csv"))).isInstanceOf(ApiException.class);
        for (boolean formula:List.of(false,true)) {
            var file=temp.resolve("invalid.xlsx");
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var stream=Files.newOutputStream(file)) {
                var sheet=book.createSheet(); if(formula) { sheet.createRow(0).createCell(0).setCellFormula("1+1"); } book.write(stream);
            }
            assertThatThrownBy(() -> PlayoffTable.read(file)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_18_01_missingUnknownAndMalformedColumnsReportPositions() throws Exception {
        var dir=copy(); var table=PlayoffTable.read(dir.resolve("socis.csv"));
        table.set(0,new ArrayList<>(table.getFirst()));table.getFirst().set(10,"Wrong");PlayoffTable.write(dir.resolve("socis.csv"),table);
        assertThat(new MigrationReport(true,PlayoffInput.read(dir,mapping).incidents()).render()).contains("birthDate@11");
        table.getFirst().set(10,"Data naixement");
        for(int i=0;i<table.size();i++){table.set(i,new ArrayList<>(table.get(i)));table.get(i).add("extra");}
        PlayoffTable.write(dir.resolve("socis.csv"),table);
        assertThat(new MigrationReport(true,PlayoffInput.read(dir,mapping).incidents()).render()).contains("extraColumnsFrom@52");
        assertThatThrownBy(() -> new PlayoffAnonymizer(key).anonymize(dir,temp.resolve("unsafe"),mapping)).isInstanceOf(ApiException.class);
        table.get(1).removeLast();PlayoffTable.write(dir.resolve("socis.csv"),table);
        assertThat(new MigrationReport(true,PlayoffInput.read(dir,mapping).incidents()).hasErrors()).isTrue();
        Files.writeString(dir.resolve("socis.csv"),"\"open");assertThat(PlayoffInput.read(dir,mapping).incidents()).isNotEmpty();
        Files.writeString(dir.resolve("socis.csv"),"");assertThat(PlayoffInput.read(dir,mapping).incidents()).isNotEmpty();
        Files.delete(dir.resolve("family_groups.csv"));assertThat(PlayoffInput.read(dir,mapping).files().get("groups")).isEmpty();
        assertThatThrownBy(() -> MappingConfig.load(temp.resolve("missing.yaml"))).isInstanceOf(ApiException.class);
        Files.writeString(temp.resolve("invalid.yaml"),"version: 99");
        assertThatThrownBy(() -> MappingConfig.load(temp.resolve("invalid.yaml"))).isInstanceOf(ApiException.class);
    }
    @Test void T_18_08_commandsValidateOptionsAndProductionProfiles() throws Exception {
        var importer=mock(PlayoffImportService.class);var clubs=mock(MigrationClubAccess.class);var env=new MockEnvironment();
        var command=new PlayoffCommand(importer,clubs,env);assertThat(command.name()).isEqualTo("migration:playoff");
        when(clubs.resolve(anyString())).thenReturn("club");
        when(importer.importDirectory(any(),any(),anyString(),anyBoolean(),anyBoolean(),anyBoolean())).thenReturn(new MigrationReport(true,List.of()));
        command.run(new DefaultApplicationArguments("input","--dry-run"));
        verify(importer).importDirectory(Path.of("input"),mapping,"club",true,false,false);
        env.setActiveProfiles("prod");command.run(new DefaultApplicationArguments("input","--confirm-production","--club=sample","--env=staging"));
        verify(importer).importDirectory(Path.of("input"),mapping,"club",false,true,true);
        for(String[] args:List.of(new String[]{},new String[]{"input","--bad"},new String[]{"input","--dry-run=true"},new String[]{"input","--club"},
                new String[]{"input","--club="},new String[]{"input","--env=unknown"},new String[]{"input","--club=a","--club=b"})) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        }
        when(importer.importDirectory(any(),any(),anyString(),anyBoolean(),anyBoolean(),anyBoolean())).thenReturn(new MigrationReport(true,List.of(new MigrationReport.Entry("members",2,"members","ERROR","MAPPING_INVALID"))));
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("input"))).isInstanceOf(ApiException.class);
        var source=copy(); AnonymizeCommand.run(new DefaultApplicationArguments(source.toString(),temp.resolve("command").toString()),key);
        for(String[] args:List.of(new String[]{},new String[]{"in","out","--bad"},new String[]{"in","out","--mapping"},new String[]{"in","out","--mapping="})) {
            assertThatThrownBy(() -> AnonymizeCommand.run(new DefaultApplicationArguments(args),key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MigrationBankVault("").requireKey()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new MigrationBankVault("not-base64").requireKey()).isInstanceOf(ApiException.class);
    }
}
