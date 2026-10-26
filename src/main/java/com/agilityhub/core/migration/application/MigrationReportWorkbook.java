package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.MigrationRun;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** A short-lived private workbook. Names appear here only, never in the persisted incident rows or CLI report. */
@Component
public class MigrationReportWorkbook {
    public Path write(MigrationRun run, PlayoffInput input) {
        Path file=null;
        try {
            file=Files.createTempFile("migration-report-", ".xlsx",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var workbook=new XSSFWorkbook(); var stream=Files.newOutputStream(file)) {
                var summary=workbook.createSheet("Resum"); row(summary,"Camp","Valor");
                row(summary,"Execució",run.id()); row(summary,"Estat",run.status()); row(summary,"Entorn",run.env());
                row(summary,"Finalització",run.finishedAt().toString()); row(summary,"Caducitat",run.reportExpiresAt().toString());
                row(summary,"SHA-256 del mapatge",run.inputManifest().mappingSha256());
                if (run.execution()!=null) {
                    var execution=run.execution();
                    row(summary,"Passos seleccionats",execution.selectedSteps().toString());
                    row(summary,"Passos acabats en aquesta execució",execution.completedThisRun().toString());
                    row(summary,"Passos acabats abans",execution.completedBefore().toString());
                    row(summary,"Tota la seqüència acabada",execution.pipelineComplete() ? "Sí" : "No");
                    row(summary,"Data del tall",execution.cutover().toString()); row(summary,"Execució inicial",execution.rootRunId());
                    row(summary,"Execució anterior",execution.priorRunId()); row(summary,"Ordinal",execution.ordinal());
                    row(summary,"SHA-256 de la confirmació de reexecució",execution.approvalDigest());
                }
                row(summary,"Interpretació dels resultats","FAILED".equals(run.status())
                        ? "Només canvis previstos. Una càrrega fallida pot haver desat lots anteriors; aquestes files no acrediten quins canvis s'han aplicat."
                        : "Resultats de la importació completada. Cal revisar els avisos i les propostes.");
                new TreeMap<>(run.counters()).forEach((key,value)->row(summary,key,value));
                var manifest=workbook.createSheet("Manifest d'entrada"); row(manifest,"Fitxer","SHA-256","Files de dades");
                run.inputManifest().files().forEach(entry->row(manifest,entry.name(),entry.sha256(),entry.rows()));
                var incidents=workbook.createSheet("FAILED".equals(run.status()) ? "Resultats previstos" : "Resultats");
                row(incidents,"Clau del fitxer d'origen","Fila d'origen","Entitat","Resultat","Codi","Camp o ID d'abonat","ID d'abonat Playoff","Nom");
                var members=new HashMap<String,PlayoffInput.Row>();
                input.files().getOrDefault("members",List.of()).forEach(member->members.put(member.get("id"),member));
                for (var entry:run.reportRows()) {
                    var source=input.files().getOrDefault(entry.file(),List.of()).stream().filter(candidate->candidate.row()==entry.row()).findFirst().orElse(null);
                    String id=source==null ? "" : source.get("memberId");
                    // These adapters define `id` as the member ID; receipts/packs use an independent object ID.
                    if (source!=null && id.isEmpty() && Set.of("members","plans","levels","groups").contains(entry.file())) { id=source.get("id"); }
                    var member=members.get(id);
                    String name=member==null ? "" : (member.get("firstName")+" "+member.get("surname")).strip();
                    row(incidents,entry.file(),entry.row(),entry.entity(),entry.outcome(),entry.code(),entry.field(),id,name);
                }
                for (var sheet:workbook) { sheet.createFreezePane(0,1); for (int column=0;column<sheet.getRow(0).getLastCellNum();column++) { sheet.setColumnWidth(column,Math.min(column==7 ? 45 : 28,255)*256); } }
                workbook.write(stream);
            }
            return file;
        } catch (IOException | RuntimeException failure) {
            if (file!=null) { try { Files.deleteIfExists(file); } catch(IOException cleanup) { failure.addSuppressed(cleanup); } }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            throw new UncheckedIOException((IOException)failure);
        }
    }
    /** Explicit string cells: values beginning with =,+,-,@ remain inert text. */
    private static void row(Sheet sheet,Object... values) {
        var row=sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i=0;i<values.length;i++) {
            var cell=row.createCell(i);
            if (values[i] instanceof Number number) { cell.setCellValue(number.doubleValue()); }
            else { cell.setCellValue(Objects.toString(values[i],"")); }
        }
    }
}
