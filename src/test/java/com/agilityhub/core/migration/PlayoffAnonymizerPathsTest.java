package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.PlayoffAnonymizer;
import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlayoffAnonymizerPathsTest {
    @TempDir Path temp;
    private final MappingConfig mapping = MappingConfig.load(null);
    private final PlayoffAnonymizer anonymizer = new PlayoffAnonymizer("fictional-one-use-key-for-path-tests-2026");

    private Path fixture(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path source = Path.of("src/test/resources/fixtures/playoff");
        for (var schema : mapping.files().values()) {
            if (Files.exists(source.resolve(schema.name()))) {
                Files.copy(source.resolve(schema.name()), directory.resolve(schema.name()));
            }
        }
        return directory;
    }

    private void refusedBeforeOutput(Path input, Path output) {
        assertThatThrownBy(() -> anonymizer.anonymize(input, output, mapping))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INPUT_SCHEMA_MISMATCH));
        assertThat(output).doesNotExist();
    }

    @Test void T_18_12_anonymizerRejectsRawExportsInsideGitAndWorktreeBeforeWriting() throws Exception {
        for (boolean worktree : new boolean[]{false, true}) {
            Path repository = Files.createDirectory(temp.resolve(worktree ? "worktree" : "repository"));
            if (worktree) { Files.writeString(repository.resolve(".git"), "gitdir: fictional\n"); }
            else { Files.createDirectory(repository.resolve(".git")); }
            refusedBeforeOutput(fixture(repository.resolve("exports")), temp.resolve(worktree ? "out-worktree" : "out-repository"));
        }
    }

    @Test void T_18_12_anonymizerRejectsDropboxAndAnAliasPointingIntoIt() throws Exception {
        Path input = fixture(temp.resolve("Dropbox-personal/exports"));
        refusedBeforeOutput(input, temp.resolve("out-direct"));
        Path alias = Files.createSymbolicLink(temp.resolve("outside-alias"), input);
        refusedBeforeOutput(alias, temp.resolve("out-alias"));
    }

    @Test void T_18_12_anonymizerRejectsSymlinkedExportFilesBeforeReading() throws Exception {
        Path input = fixture(temp.resolve("exports"));
        Path memberFile = input.resolve("socis.csv");
        Path outside = Files.move(memberFile, temp.resolve("outside.csv"));
        Files.createSymbolicLink(memberFile, outside);
        refusedBeforeOutput(input, temp.resolve("out-symlink"));
    }

    @Test void T_18_03_anonymizerAcceptsPrivateExportDirectoryAndKeepsInputUntouched() throws Exception {
        Path input = fixture(temp.resolve("private-exports"));
        byte[] original = Files.readAllBytes(input.resolve("socis.csv"));
        Path output = temp.resolve("anonymized");
        anonymizer.anonymize(input, output, mapping);
        assertThat(output.resolve("socis.csv")).exists();
        assertThat(Files.readAllBytes(input.resolve("socis.csv"))).isEqualTo(original);
        assertThat(Files.readAllBytes(output.resolve("socis.csv"))).isNotEqualTo(original);
    }
}
