package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.common.application.*;
import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.clubs.followup.application.*;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.persistence.VolumeMigrationReportStore;
import com.agilityhub.core.shared.application.PrivateFileStorageSettings;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PrivateFileStorageConfigurationTest {
    @TempDir Path temporary;
    Path root;
    String key;
    @BeforeEach void volume() throws Exception {
        root = temporary.toRealPath().resolve("volume");
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(root.resolve(PrivateFileStorageSettings.MARKER), PrivateFileStorageSettings.MARKER_CONTENT,
                StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(root.resolve(PrivateFileStorageSettings.MARKER), PosixFilePermissions.fromString("rw-------"));
        key = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    }
    MockEnvironment environment() {
        var env = new MockEnvironment().withProperty("core.storage.mode", "filesystem")
                .withProperty("core.storage.root", root.toString()).withProperty("core.storage.signing-key", key)
                .withProperty("core.hosted-demo.enabled", "true").withProperty("email.delivery-enabled", "false");
        env.setActiveProfiles("prod"); return env;
    }
    ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withUserConfiguration(ExportConfiguration.class, AttachmentConfiguration.class, MigrationReportConfiguration.class)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC))
                .withBean(ListExportRepository.class, () -> mock(ListExportRepository.class))
                .withBean(ExportWorkRepository.class, () -> mock(ExportWorkRepository.class))
                .withBean(AttachmentRepository.class, () -> mock(AttachmentRepository.class))
                .withPropertyValues("spring.profiles.active=" + profile, "core.storage.mode=filesystem", "core.storage.root=" + root,
                        "core.storage.signing-key=" + key, "core.hosted-demo.enabled=true", "email.delivery-enabled=false");
    }
    @ParameterizedTest @ValueSource(strings = {"prod", "staging"})
    void E0_T13_internalVolumeHasAllPrivateAdaptersAndNoS3CredentialsOrClients(String profile) {
        runner(profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ExportStorage.class)).isInstanceOf(LocalExportStorage.class);
            assertThat(context.getBean(AttachmentStorage.class)).isInstanceOf(LocalAttachmentStorage.class);
            assertThat(context.getBean(MigrationReportStore.class)).isInstanceOf(VolumeMigrationReportStore.class);
            assertThat(context).doesNotHaveBean(S3Client.class).doesNotHaveBean(S3Presigner.class);
            assertThat(root.resolve("exports")).isDirectory();
            assertThat(root.resolve("attachments")).isDirectory();
            assertThat(root.resolve("migration-reports")).isDirectory();
        });
    }
    @ParameterizedTest @ValueSource(strings = {"local", "test", "prod,local", "staging,test"})
    void E0_T13_explicitFilesystemNeverActivatesDevelopmentProvidersInDeployment(String profiles) {
        runner(profiles).run(context -> assertThat(context).hasFailed());
    }
    @Test void E0_T13_publicProductionRetainsS3AndRequiresItsCredentials() {
        runner("prod").withPropertyValues("core.storage.mode=s3", "core.hosted-demo.enabled=false", "email.delivery-enabled=true")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> PrivateFileStorageSettings.from(environment().withProperty("core.hosted-demo.enabled", "false")
                .withProperty("email.delivery-enabled", "true"))).hasMessageContaining("HOSTED_DEMO_ENABLED");
        assertThatThrownBy(() -> PrivateFileStorageSettings.from(environment().withProperty("core.storage.mode", "local")))
                .hasMessageContaining("FILE_STORAGE_MODE");
    }
    @Test void E0_T13_directoryAndPersistentSigningKeyAreMandatory() {
        for (String path : new String[] {"", ".local/files", "/", root + "/../volume", root + "/missing"}) {
            assertThatThrownBy(() -> PrivateFileStorageSettings.from(environment().withProperty("core.storage.root", path)))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String invalid : new String[] {"", "not base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> PrivateFileStorageSettings.from(environment().withProperty("core.storage.signing-key", invalid)))
                    .hasMessageContaining("FILE_STORAGE_SIGNING_KEY");
        }
        var settings = PrivateFileStorageSettings.from(environment());
        byte[] copy = settings.signingKey(); copy[0] ^= 1;
        assertThat(settings.signingKey()).isNotEqualTo(copy);
    }
    @Test void E0_T13_markerPermissionsAndSymlinksAreCheckedAgainBeforeIO() throws Exception {
        var settings = PrivateFileStorageSettings.from(environment());
        Path marker = root.resolve(PrivateFileStorageSettings.MARKER);
        Files.writeString(marker, "wrong-marker");
        assertThatThrownBy(settings::requireReady).isInstanceOf(IllegalStateException.class);
        Files.writeString(marker, PrivateFileStorageSettings.MARKER_CONTENT);
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(settings::requireReady).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(settings::requireReady).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path alias = root.resolveSibling("alias"); Files.createSymbolicLink(alias, root);
        assertThatThrownBy(() -> PrivateFileStorageSettings.from(environment().withProperty("core.storage.root", alias.toString())))
                .isInstanceOf(IllegalStateException.class);
        Files.delete(marker); Files.createSymbolicLink(marker, temporary.resolve("outside-marker"));
        assertThatThrownBy(settings::requireReady).isInstanceOf(IllegalStateException.class);
        Files.delete(marker);
        assertThatThrownBy(() -> settings.directory("attachments")).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("attachments")).doesNotExist();
    }
}
