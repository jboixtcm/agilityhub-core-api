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
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments"), store("exports")), new MockClock(NOW), mock(org.springframework.transaction.PlatformTransactionManager.class));
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

    /**
     * Since E11-T11 the pending files of a request in any status are deleted (see the next test); only a file of an unknown
     * store fails, for the outbox to retry, and a request with nothing pending, or none at all, is left untouched.
     */
    @Test void T_14_19_onlyAnUnknownStoreFailsForTheOutboxToRetryAndARequestWithNothingPendingIsLeftAlone() {
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments")), new MockClock(NOW), mock(org.springframework.transaction.PlatformTransactionManager.class));
        var request = new com.agilityhub.core.platform.persistence.privacy.ErasureRequest("r", "club-a", "MEMBER", null, List.of("m"), List.of("club-a"), "ADMIN",
                null, NOW, null, "ERASING", NOW, List.of(), null, null, null, List.of("nowhere:key"), List.of("club-a"), false, true, null, 4);
        when(requests.findById("r")).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> cleanup.clean("r")).isInstanceOf(IllegalStateException.class);
        verify(requests, never()).close(any(), any());
        when(requests.findById("s")).thenReturn(Optional.of(PrivacyFixtures.request("s", "MEMBER", "SCHEDULED", List.of("m"), List.of("club-a"), List.of(), null)));
        assertThat(cleanup.clean("s")).isFalse();
        verify(requests, never()).fileDeleted(eq("s"), any(), any());
        when(requests.findById("missing")).thenReturn(Optional.empty());
        assertThat(cleanup.clean("missing")).isFalse();
    }

    /**
     * E11-T11: an erased club's files leave the store although the request does not complete (it waits, is `BLOCKED` between
     * clubs or was cancelled), and the request keeps its status: only an `ERASING` one closes.
     */
    @Test void R_14_15_theFilesOfAClubErasedHalfwayLeaveTheStoreAndTheRequestKeepsItsStatus() {
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments")), new MockClock(NOW), mock(org.springframework.transaction.PlatformTransactionManager.class));
        for (String status : List.of("SCHEDULED", "BLOCKED", "CANCELLED")) {
            deleted.clear(); reset(requests);
            var request = new com.agilityhub.core.platform.persistence.privacy.ErasureRequest("r", null, "ACCOUNT", "montse-account", List.of("m", "n"),
                    List.of("club-a", "club-b"), "PLATFORM", null, NOW, null, status, NOW, List.of(), null, null, null, List.of("attachments:dogs/photo.jpg"),
                    List.of("club-a"), false, "CANCELLED".equals(status) ? null : true, null, 4);
            when(requests.findById("r")).thenReturn(Optional.of(request));
            assertThat(cleanup.clean("r")).as(status).isTrue();
            assertThat(deleted).as(status).containsExactly("attachments:dogs/photo.jpg");
            verify(requests).fileDeleted("r", "attachments:dogs/photo.jpg", NOW);
            verify(requests, never()).close(any(), any());
        }
        // After the club's part commits; without a transaction (here), at once. A store failure is left for the daily retry.
        reset(requests); deleted.clear();
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "SCHEDULED", List.of("m"), List.of("club-a"), List.of("club-a"), null)));
        cleanup.cleanAfterCommit("r");
        verify(requests).findById("r");
        when(requests.findById("broken")).thenThrow(new IllegalStateException("store down"));
        assertThatCode(() -> cleanup.cleanAfterCommit("broken")).doesNotThrowAnyException();
    }

    /** INC-74 (E11-T13, step 9): the caller hears of a cleanup that deleted a file, never of one that failed or found nothing. */
    @Test void R_14_15_INC_74_theAfterCommitCleanupTellsTheCallerOnlyWhenItCleaned() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var cleanup = new ErasureFilesCleanup(requests, List.of(store("attachments")), new MockClock(NOW), manager);
        var heard = new ArrayList<String>();
        when(requests.findById("r")).thenReturn(Optional.of(new com.agilityhub.core.platform.persistence.privacy.ErasureRequest("r", null, "ACCOUNT", "montse-account",
                List.of("m", "n"), List.of("club-a", "club-b"), "PLATFORM", null, NOW, null, "SCHEDULED", NOW, List.of(), null, null, null,
                List.of("attachments:dogs/photo.jpg"), List.of("club-a"), false, true, null, 4)));
        cleanup.cleanAfterCommit("r", () -> heard.add("r"));
        assertThat(deleted).containsExactly("attachments:dogs/photo.jpg");
        when(requests.findById("s")).thenReturn(Optional.of(PrivacyFixtures.request("s", "ACCOUNT", "SCHEDULED", List.of("m"), List.of("club-a"), List.of("club-a"), null)));
        cleanup.cleanAfterCommit("s", () -> heard.add("s"));
        when(requests.findById("broken")).thenThrow(new IllegalStateException("store down"));
        cleanup.cleanAfterCommit("broken", () -> heard.add("broken"));
        assertThat(heard).containsExactly("r");
    }
}
