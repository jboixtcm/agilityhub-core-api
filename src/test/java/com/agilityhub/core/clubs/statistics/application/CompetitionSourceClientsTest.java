package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitionSourceClientsTest {
    @Test void T_STATS_23_authenticatedFetchUsesOnlyCanonicalHostAndRejectsRedirectsLoginAndOversizedBodies() throws Exception {
        var connection = mock(Connection.class, RETURNS_SELF);
        var response = mock(Connection.Response.class);
        when(connection.execute()).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.contentType()).thenReturn("text/html; charset=utf-8");
        String body = FlowPrintResultsParserTest.html("-");
        when(response.bodyAsBytes()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        try (var jsoup = mockStatic(Jsoup.class, CALLS_REAL_METHODS); var tenant = TenantContext.open("club-a")) {
            jsoup.when(() -> Jsoup.connect(FlowPrintResultsParserTest.URL)).thenReturn(connection);
            var client = new FlowSourceClient("{\"club-a\":\"fictional-session-value-for-test\"}");
            assertThat(client.fetch(FlowPrintResultsParserTest.URL.replace("www.", "").replace("/prints/", "/") + "#list_anchor_header")).isEqualTo(body);
            verify(connection).cookie("_facom_key", "fictional-session-value-for-test");
            verify(connection).followRedirects(false);
            verify(connection).timeout(20_000);
            verify(connection).maxBodySize(CompetitionValidation.MAX_BYTES + 1);
            for (int status : new int[] {302, 303, 401, 403, 500}) {
                when(response.statusCode()).thenReturn(status);
                assertThatThrownBy(() -> client.fetch(FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
            }
            when(response.statusCode()).thenReturn(200);
            when(response.contentType()).thenReturn(null, "application/json");
            for (int attempt = 0; attempt < 2; attempt++) assertThatThrownBy(() -> client.fetch(FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
            when(response.contentType()).thenReturn("text/html");
            when(response.bodyAsBytes()).thenReturn(new byte[CompetitionValidation.MAX_BYTES + 1]);
            assertThatThrownBy(() -> client.fetch(FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
            when(response.bodyAsBytes()).thenReturn("<form action='/user/login'></form>".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> client.fetch(FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
            when(connection.execute()).thenThrow(new IOException("Timeout"));
            assertThatThrownBy(() -> client.fetch(FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> client.fetch(CompetitionParsersTest.FLOW_URL)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_STATS_24_publicFetchRejectsNonHtmlRedirectsOversizedBodiesAndTransportFailures() throws Exception {
        var connection = mock(Connection.class, RETURNS_SELF); var response = mock(Connection.Response.class);
        when(connection.execute()).thenReturn(response); when(response.statusCode()).thenReturn(200);
        when(response.contentType()).thenReturn("text/html"); when(response.bodyAsBytes()).thenReturn(CompetitionParsersTest.FCAG.getBytes(StandardCharsets.UTF_8));
        try (var jsoup = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(CompetitionParsersTest.FCAG_URL)).thenReturn(connection);
            var client = new FcagSourceClient();
            assertThat(client.fetch(CompetitionParsersTest.FCAG_URL)).isEqualTo(CompetitionParsersTest.FCAG);
            verify(connection).followRedirects(false);
            when(response.statusCode()).thenReturn(302);
            assertThatThrownBy(() -> client.fetch(CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
            when(response.statusCode()).thenReturn(200); when(response.contentType()).thenReturn(null, "application/json");
            for (int attempt = 0; attempt < 2; attempt++) assertThatThrownBy(() -> client.fetch(CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
            when(response.contentType()).thenReturn("text/html"); when(response.bodyAsBytes()).thenReturn(new byte[CompetitionValidation.MAX_BYTES + 1]);
            assertThatThrownBy(() -> client.fetch(CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
            when(connection.execute()).thenThrow(new IOException("Timeout"));
            assertThatThrownBy(() -> client.fetch(CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
        }
    }
}
