package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportLifecycleTest {
    Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
    ListExportRepository jobs = mock(ListExportRepository.class);
    ExportStorage storage = mock(ExportStorage.class);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); }
    void auth(String role, boolean imp) {
        var jwt = Jwt.withTokenValue("fictional").header("alg", "none").subject("owner").claim("imp", imp).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_" + role)));
    }
    @Test void T_14_16_applicationOwnershipAndRoleChecksCannotBeBypassed() throws Exception {
        var queries = new ExportQueries(jobs, storage, clock, new IcuMessageSource());
        assertThatThrownBy(() -> queries.get("job")).isInstanceOf(ApiException.class);
        auth("ADMIN", true); assertThatThrownBy(() -> queries.get("job")).isInstanceOf(ApiException.class);
        auth("INSTRUCTOR", false); assertThatThrownBy(() -> queries.get("job")).isInstanceOf(ApiException.class);
        try (var tenant = TenantContext.open("club")) {
            auth("MEMBER", false); assertThatThrownBy(() -> queries.list(null)).isInstanceOf(ApiException.class);
            auth("ADMIN", false);
            var job = mock(ExportJob.class); when(job.ownerAccountId()).thenReturn("owner"); when(job.status()).thenReturn("QUEUED");
            when(jobs.findById("job")).thenReturn(Optional.of(job));
            assertThat(queries.get("job").downloadUrl()).isNull();
            assertThatThrownBy(() -> queries.download("job", 0, "invalid")).isInstanceOf(ApiException.class);
            when(job.status()).thenReturn("READY"); when(job.expiresAt()).thenReturn(clock.instant().plusSeconds(60));
            assertThatThrownBy(() -> queries.download("job", 0, "invalid")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
            when(job.expiresAt()).thenReturn(clock.instant());
            assertThatThrownBy(() -> queries.get("job")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPORT_EXPIRED));
        }
    }
    @Test void T_14_16_workerDoesNotOverlapAndContinuesAfterAClubCleanupFailure() {
        var work = mock(ExportWorkRepository.class); var coordinator = mock(ExportJobCoordinator.class); var renderer = mock(ExportJobRenderer.class);
        var worker = new ExportWorker(work, jobs, coordinator, renderer, storage, clock);
        when(work.clubs(clock.instant())).thenReturn(List.of("club-a", "club-b"));
        var job = mock(ExportJob.class); when(job.fileKeys()).thenReturn(List.of("file")); when(job.fileKey()).thenReturn("file");
        when(jobs.expired(clock.instant())).thenAnswer(call -> TenantContext.require().equals("club-a") ? List.of(job) : List.of());
        doThrow(new IllegalStateException("Storage unavailable")).doNothing().when(storage).delete("file");
        when(coordinator.claim()).thenReturn(job);
        when(renderer.render(job, false)).thenAnswer(call -> { worker.poll(); return null; });
        worker.poll();
        verify(work, times(1)).clubs(clock.instant()); verify(coordinator, times(1)).claim(); verify(jobs, never()).cleaned(any(), any());
        worker.poll(); verify(jobs).cleaned(job, clock.instant());
        assertThat(TenantContext.current()).isNull();
        when(renderer.render(job, false)).thenThrow(new IllegalStateException("Storage unavailable"));
        worker.poll(); verify(coordinator, times(2)).fail(eq(job), any());
    }
    @Test void T_14_15_pdfUsesLandscapeAndTranslatedPageTotals() throws Exception {
        var renderer = new ListExportRenderer(); var output = new java.io.ByteArrayOutputStream();
        var columns = List.of("fullName", "dogs", "plan", "displayStatus", "city", "birthDate", "gender");
        renderer.renderTo("pdf", "Fictional club", "#112233", "members", columns, List.of(Map.of("fullName", "Fictional Person")), Locale.forLanguageTag("en"), ZoneOffset.UTC, output);
        try (var document = org.apache.pdfbox.Loader.loadPDF(output.toByteArray())) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isGreaterThan(document.getPage(0).getMediaBox().getHeight());
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Page 1 of 1", "Member", "Fictional club");
        }
    }
}
