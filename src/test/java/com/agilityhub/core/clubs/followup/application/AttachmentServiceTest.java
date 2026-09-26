package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentServiceTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final String key = UUID.randomUUID().toString();
    private final UploadGrantRepository grants = mock(UploadGrantRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final ClubConfigService configs = mock(ClubConfigService.class);
    private final DogOwnerAccess dogs = mock(DogOwnerAccess.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final AttachmentService service = new AttachmentService(grants, attachments, storage, configs, dogs, Clock.fixed(now, ZoneOffset.UTC), events);
    private TenantContext.Scope tenant;
    private CurrentUser.Scope user;
    @BeforeEach void prepare() {
        tenant = TenantContext.open("example-club"); user = CurrentUser.open(new CurrentUser("example-account", "Example", null, DomainEvent.Origin.APP));
        config(Set.of(Module.TASKS));
        when(storage.metadata(key)).thenReturn(new AttachmentStorage.Metadata("text/plain", 4));
        when(storage.downloadUrl(anyString(), anyString(), any())).thenReturn("https://files.example.test/temporary");
    }
    @AfterEach void close() { user.close(); tenant.close(); }
    private void config(Set<Module> modules) {
        when(configs.get("example-club")).thenReturn(new ClubConfig(null,
                Map.of("files.allowedTypes", List.of("image/*", "text/plain"), "files.maxSizeMb", 25, "files.dogPhotoMaxMb", 8, "files.maxAttachmentsPerEntity", 10), modules, null, Map.of()));
    }
    private void grant(String account, String purpose, String bound, Instant expiry) {
        when(grants.findById(key)).thenReturn(Optional.of(new UploadGrant(key, "example-club", account, purpose, "Example.txt", "text/plain", 4, now, expiry, bound)));
    }
    @Test void T_03_23_uploadPolicyRejectsUnsafeNamesTypesSizesAndDisabledNotes() {
        for (String name : Arrays.asList(null, "", "x".repeat(256), "bad\rname", "bad\nname")) {
            assertThatThrownBy(() -> service.upload("DOG_DOCUMENT", name, "text/plain", 4)).hasMessage("VALIDATION_ERROR");
        }
        for (String type : Arrays.asList(null, "application/x-executable")) {
            assertThatThrownBy(() -> service.upload("DOG_DOCUMENT", "Example", type, 4)).hasMessage("FILE_TYPE_NOT_ALLOWED");
        }
        assertThatThrownBy(() -> service.upload("DOG_PHOTO", "Example", "text/plain", 4)).hasMessage("FILE_TYPE_NOT_ALLOWED");
        for (long size : List.of(0L, -1L, 26L * 1024 * 1024)) {
            assertThatThrownBy(() -> service.upload("DOG_DOCUMENT", "Example", "text/plain", size)).hasMessage("FILE_TOO_LARGE");
        }
        assertThatThrownBy(() -> service.upload("OTHER", "Example", "text/plain", 4)).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        config(Set.of());
        for (String purpose : List.of("INSTRUCTOR_NOTE", "TASK", "DOG_OBSERVATIONS")) {
            assertThatThrownBy(() -> service.upload(purpose, "Example", "text/plain", 4)).hasMessage("MODULE_DISABLED");
        }
        verifyNoInteractions(grants, events);
    }
    @Test void T_10_16_taskAndObservationPurposesFollowTheS10FilePolicy() {
        when(storage.uploadUrl(anyString(), anyString(), anyLong(), any())).thenReturn("https://files.example.test/upload");
        for (String purpose : List.of("TASK", "DOG_OBSERVATIONS")) {
            assertThat(service.upload(purpose, "vídeo_balancí.mov", "text/plain", 20L * 1024 * 1024).uploadUrl()).isEqualTo("https://files.example.test/upload");
            assertThatThrownBy(() -> service.upload(purpose, "vídeo_balancí.mp4", "text/plain", 30L * 1024 * 1024)).hasMessage("FILE_TOO_LARGE")
                    .extracting(error -> ((ApiException) error).details()).isEqualTo(Map.of("maxSizeMb", 25));
            assertThatThrownBy(() -> service.upload(purpose, "Example.exe", "application/x-msdownload", 4)).hasMessage("FILE_TYPE_NOT_ALLOWED");
        }
        verify(grants, times(2)).insert(any(UploadGrant.class));
    }
    @Test void T_07_18_activityPurposesUseTheModuleAllowedMimeTypesAndGeneralSizeLimit() {
        for (String purpose : List.of("ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT")) {
            config(Set.of());
            assertThatThrownBy(() -> service.upload(purpose, "Example", "image/png", 4)).hasMessage("MODULE_DISABLED");
            config(Set.of(Module.ACTIVITIES));
            assertThatThrownBy(() -> service.upload(purpose, "Example", "application/x-executable", 4)).hasMessage("FILE_TYPE_NOT_ALLOWED");
            assertThatThrownBy(() -> service.upload(purpose, "Example", "image/png", 26L * 1024 * 1024)).hasMessage("FILE_TOO_LARGE");
            assertThat(service.upload(purpose, "Example", "image/png", 9L * 1024 * 1024).fileKey()).isNotBlank();
        }
        assertThatThrownBy(() -> service.upload("ACTIVITY_IMAGE", "Example", "text/plain", 4)).hasMessage("FILE_TYPE_NOT_ALLOWED");
        assertThat(service.upload("ACTIVITY_DOCUMENT", "Example", "text/plain", 4).fileKey()).isNotBlank();
        verify(grants, times(3)).insert(any());
        verifyNoInteractions(events);
    }
    @Test void T_03_23_claimsRequireTheUploaderPurposeAndExactEntity() {
        grant("someone-else", "DOG_DOCUMENT", null, now.plusSeconds(300));
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog:VACCINATION_CARD")).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        grant("example-account", "DOG_PHOTO", null, now.plusSeconds(300));
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog:VACCINATION_CARD")).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        grant("example-account", "DOG_DOCUMENT", "another-dog", now.plusSeconds(300));
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog:VACCINATION_CARD")).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        grant("example-account", "DOG_DOCUMENT", null, now);
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog:VACCINATION_CARD")).hasMessage("INVALID_STATE");
        verify(grants, never()).bind(anyString(), anyString());
        grant("example-account", "DOG_DOCUMENT", "dog:VACCINATION_CARD", now);
        assertThat(service.claim(key, "DOG_DOCUMENT", "dog:VACCINATION_CARD").fileKey()).isEqualTo(key);
        verify(grants).bind(key, "dog:VACCINATION_CARD");
    }
    @Test void T_03_23_claimsCheckUploadedBytesAgainstTheGrant() {
        grant("example-account", "DOG_DOCUMENT", null, now.plusSeconds(300));
        when(storage.metadata(key)).thenReturn(new AttachmentStorage.Metadata("text/plain", 5));
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog")).hasMessage("FILE_TOO_LARGE");
        when(storage.metadata(key)).thenReturn(new AttachmentStorage.Metadata("image/png", 4));
        assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog")).hasMessage("FILE_TYPE_NOT_ALLOWED");
        verify(grants, never()).bind(anyString(), anyString());
        when(grants.findById(key)).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.claim(key, "DOG_DOCUMENT", "dog")).hasMessage("NOT_FOUND");
    }
    @Test void T_03_23_localRoutesAreUnavailableForTheS3AdapterAndAnonymousUploadsFail() {
        assertThatThrownBy(() -> service.openLocal(key, 1, "bad")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> service.putLocal(key, 1, "bad", "text/plain", InputStream.nullInputStream())).hasMessage("NOT_FOUND");
        assertThat(service.url(null, "Example")).isNull();
        try (var anonymous = CurrentUser.open(null)) {
            assertThatThrownBy(() -> service.upload("DOG_DOCUMENT", "Example", "text/plain", 4)).hasMessage("UNAUTHENTICATED");
        }
    }
    @Test void T_03_25_noteAttachmentsCannotBeReboundOrAddedWhenDisabled() {
        config(Set.of()); assertThatThrownBy(() -> service.addNote("INSTRUCTOR_NOTE", "dog", key, "Example")).hasMessage("MODULE_DISABLED");
        config(Set.of(Module.TASKS));
        for (String name : Arrays.asList(null, "", "x".repeat(81))) {
            assertThatThrownBy(() -> service.addNote("INSTRUCTOR_NOTE", "dog", key, name)).hasMessage("VALIDATION_ERROR");
        }
        for (var pair : List.of(List.of("other-dog", "example-account"), List.of("dog", "other-account"))) {
            when(attachments.forKey(key)).thenReturn(Optional.of(new Attachment(key, "example-club", "INSTRUCTOR_NOTE", pair.get(0), key,
                    "Example", "text/plain", 4, pair.get(1), now, now, null, null, 0)));
            assertThatThrownBy(() -> service.addNote("INSTRUCTOR_NOTE", "dog", key, "Example")).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        }
        verifyNoInteractions(events);
    }
    /**
     * E5-T24 (R-04-19, amended 26-09): D2's new file is the caller's own DOG_DOCUMENT upload of the club, unclaimed or claimed for
     * the same dog and type; anything else is FILE_NOT_FOUND. The uploaded bytes must match the grant, and the claim binds it.
     */
    @Test void R_04_19_d2ClaimsOnlyTheCallersOwnDogDocumentUpload() {
        when(grants.findById(key)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_NOT_FOUND");
        grant("example-account", "DOG_PHOTO", null, now.plusSeconds(300));
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_NOT_FOUND");
        grant("someone-else", "DOG_DOCUMENT", null, now.plusSeconds(300));
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_NOT_FOUND");
        grant("example-account", "DOG_DOCUMENT", "other-dog:VACCINATION_CARD", now.plusSeconds(300));
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_NOT_FOUND");
        grant("example-account", "DOG_DOCUMENT", null, now.plusSeconds(300));
        when(storage.metadata(key)).thenThrow(new ApiException(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).as("never uploaded").hasMessage("FILE_NOT_FOUND");
        doThrow(new ApiException(ErrorCode.VALIDATION_ERROR)).when(storage).metadata(key);
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("VALIDATION_ERROR");
        doReturn(new AttachmentStorage.Metadata("text/plain", 5)).when(storage).metadata(key);
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_TOO_LARGE");
        doReturn(new AttachmentStorage.Metadata("image/png", 4)).when(storage).metadata(key);
        assertThatThrownBy(() -> service.claimDogDocument(key, "dog:VACCINATION_CARD")).hasMessage("FILE_TYPE_NOT_ALLOWED");
        verify(grants, never()).bind(anyString(), anyString());
        doReturn(new AttachmentStorage.Metadata("text/plain", 4)).when(storage).metadata(key);
        // An expired upload URL does not matter: the file waits for the D2 save.
        grant("example-account", "DOG_DOCUMENT", null, now.minusSeconds(1));
        var claimed = service.claimDogDocument(key, "dog:VACCINATION_CARD");
        assertThat(claimed.id()).isEqualTo(key); assertThat(claimed.fileKey()).isEqualTo(key); assertThat(claimed.uploadedByAccountId()).isEqualTo("example-account");
        grant("example-account", "DOG_DOCUMENT", "dog:VACCINATION_CARD", now);
        assertThat(service.claimDogDocument(key, "dog:VACCINATION_CARD").fileKey()).as("claimed again for the same dog and type").isEqualTo(key);
        verify(grants, times(2)).bind(key, "dog:VACCINATION_CARD");
    }
    /**
     * E5-T24 (CONVENCIONS_API §5, A31): the local signed URLs are authorised by their signature, checked before any lookup, for
     * the grant they name whatever the caller's tenant; nobody needs to be signed in.
     */
    @Test void CONVENCIONS_API_5_localSignedUrlsAuthoriseThemselves(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var local = new LocalAttachmentStorage(directory, "fictional-signing-key-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8), Clock.fixed(now, ZoneOffset.UTC));
        var signed = new AttachmentService(grants, attachments, local, configs, dogs, Clock.fixed(now, ZoneOffset.UTC), events);
        var upload = java.net.URI.create(local.uploadUrl(key, "text/plain", 4, now.plusSeconds(300)));
        long expires = now.plusSeconds(300).getEpochSecond(); String signature = upload.getQuery().substring(upload.getQuery().indexOf("signature=") + 10);
        assertThatThrownBy(() -> signed.putLocal(key, expires, "wrong", "text/plain", new ByteArrayInputStream(new byte[4]))).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> signed.putLocal(key, now.getEpochSecond(), signature, "text/plain", new ByteArrayInputStream(new byte[4]))).hasMessage("FORBIDDEN");
        verify(grants, never()).signed(anyString());
        when(grants.signed(key)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> signed.putLocal(key, expires, signature, "text/plain", new ByteArrayInputStream(new byte[4]))).hasMessage("NOT_FOUND");
        when(grants.signed(key)).thenReturn(Optional.of(new UploadGrant(key, "another-club", "another-account", "DOG_DOCUMENT", "Example.txt", "text/plain", 4, now, now.plusSeconds(300), null)));
        try (var anonymous = CurrentUser.open(null)) {
            assertThatThrownBy(() -> signed.putLocal(key, expires, signature, "image/png", new ByteArrayInputStream(new byte[4]))).hasMessage("FILE_TYPE_NOT_ALLOWED");
            assertThatThrownBy(() -> signed.openLocal(key, expires, local.downloadUrl(key, "x", now.plusSeconds(300)).split("signature=")[1])).as("not claimed yet").hasMessage("NOT_FOUND");
            signed.putLocal(key, expires, signature, "text/plain", new ByteArrayInputStream("text".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertThat(local.metadata(key)).isEqualTo(new AttachmentStorage.Metadata("text/plain", 4));
        when(grants.signed(key)).thenReturn(Optional.of(new UploadGrant(key, "another-club", "another-account", "DOG_DOCUMENT", "Example.txt", "text/plain", 4, now, now.plusSeconds(300), "dog:VACCINATION_CARD")));
        assertThatThrownBy(() -> signed.putLocal(key, expires, signature, "text/plain", new ByteArrayInputStream(new byte[4]))).as("claimed").hasMessage("INVALID_STATE");
        String download = local.downloadUrl(key, "x", now.plusSeconds(300)).split("signature=")[1];
        assertThatThrownBy(() -> signed.openLocal(key, expires, signature)).as("the upload's signature").hasMessage("FORBIDDEN");
        try (var anonymous = CurrentUser.open(null); var input = signed.openLocal(key, expires, download)) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("text");
        }
        verify(grants, never()).findById(key);
    }
    /** E5-T19 (R-04-08, R-04-19): a signup file's id is a plain one, never its key with `/`; a re-claim of the key keeps it. */
    @Test void R_04_08_R_04_19_aClaimedSignupFileHasAPlainIdThatIsNotItsStorageKey() {
        String signupKey = "signup/example-club/202601/" + UUID.randomUUID() + "/card.txt";
        when(grants.findById(signupKey)).thenReturn(Optional.of(new UploadGrant(signupKey, "example-club", null, "SIGNUP_DOCUMENT", "card.txt", "text/plain", 4, now, now.plusSeconds(900), null)));
        when(storage.metadata(signupKey)).thenReturn(new AttachmentStorage.Metadata("text/plain", 4));
        var claimed = service.claimSignup(signupKey, "dog:VACCINATION_CARD");
        assertThat(claimed.fileKey()).isEqualTo(signupKey);
        assertThat(claimed.id()).doesNotContain("/").isEqualTo(UUID.fromString(claimed.id()).toString());
        assertThat(service.claimSignup(signupKey, "dog:VACCINATION_CARD").id()).as("the same key, the same id").isEqualTo(claimed.id());
        assertThat(AttachmentService.signupFileId(signupKey.replace("card.txt", "other.txt"))).isNotEqualTo(claimed.id());
    }
}
