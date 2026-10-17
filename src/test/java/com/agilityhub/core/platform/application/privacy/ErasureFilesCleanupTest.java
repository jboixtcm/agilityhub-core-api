package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.support.MockClock;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 §7 `ErasureFilesCleanup` (T-14-19: the photo and the documents leave the store, then `ERASED`). */
class ErasureFilesCleanupTest {
    static final Instant NOW = Instant.parse("2026-09-30T05:01:00Z");
    final ErasureRequestRepository requests = mock(ErasureRequestRepository.class);
    final List<String> deleted = new ArrayList<>();
    PrivacyFileStore store(String name) {
        return new PrivacyFileStore() {
            public String store() { return name; }
            public InputStream read(String key) { throw new UnsupportedOperationException(); }
            public void delete(String key) { deleted.add(name + ":" + key); }
        };
    }

    @Test void T_14_19_everyPendingFileIsDeletedFromItsStoreAndTheRequestCloses() {
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments"), store("exports")), new MockClock(NOW));
        var request = new com.agilityhub.core.platform.persistence.privacy.ErasureRequest("r", "club-a", "MEMBER", null, List.of("m"), List.of("club-a"), "ADMIN",
                null, NOW, null, "ERASING", NOW, List.of(), null, null, null, List.of("attachments:dogs/photo.jpg", "exports:exports/club-a/x.zip"), List.of("club-a"),
                false, true, null, 4);
        when(requests.findById("r")).thenReturn(Optional.of(request));
        when(requests.close("r", NOW)).thenReturn(true);
        assertThat(cleanup.clean("r")).isTrue();
        assertThat(deleted).containsExactly("attachments:dogs/photo.jpg", "exports:exports/club-a/x.zip");
        verify(requests).fileDeleted("r", "attachments:dogs/photo.jpg", NOW);
        verify(requests).fileDeleted("r", "exports:exports/club-a/x.zip", NOW);
    }

    @Test void T_14_19_anUnknownStoreFailsForTheOutboxToRetryAndOtherStatesAreLeftAlone() {
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments")), new MockClock(NOW));
        var request = new com.agilityhub.core.platform.persistence.privacy.ErasureRequest("r", "club-a", "MEMBER", null, List.of("m"), List.of("club-a"), "ADMIN",
                null, NOW, null, "ERASING", NOW, List.of(), null, null, null, List.of("nowhere:key"), List.of("club-a"), false, true, null, 4);
        when(requests.findById("r")).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> cleanup.clean("r")).isInstanceOf(IllegalStateException.class);
        verify(requests, never()).close(any(), any());
        when(requests.findById("s")).thenReturn(Optional.of(PrivacyFixtures.request("s", "MEMBER", "SCHEDULED", List.of("m"), List.of("club-a"), List.of(), null)));
        assertThat(cleanup.clean("s")).isFalse();
        when(requests.findById("missing")).thenReturn(Optional.empty());
        assertThat(cleanup.clean("missing")).isFalse();
    }
}
