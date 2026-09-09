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
        assertThatThrownBy(() -> service.upload("TASK", "Example", "text/plain", 4)).hasMessage("ATTACHMENT_ENTITY_MISMATCH");
        config(Set.of()); assertThatThrownBy(() -> service.upload("INSTRUCTOR_NOTE", "Example", "text/plain", 4)).hasMessage("MODULE_DISABLED");
        verifyNoInteractions(grants, events);
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
}
