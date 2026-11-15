package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.application.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

/** Per-club sessions come from the deployment environment. Credentials never reach API responses or logs. */
@Component
public class FlowSourceClient {
    private final String sessions;
    public FlowSourceClient(@Value("${app.competition.flow.sessions:}") String sessions) { this.sessions = sessions; }
    public String fetch(String sourceUrl) {
        String safe = url(CompetitionModels.Source.FLOW_AGILITY, sourceUrl);
        var run = FlowPrintResultsParser.RUN.matcher(URI.create(safe).getPath());
        if (!run.matches()) { throw invalid("FLOW_RUN_URL_REQUIRED"); }
        String cookie = sessionCookie();
        // Exact canonical destination; never forward a session through redirects or to a caller-supplied host.
        String endpoint = "https://www.flowagility.com/zone/prints/run/" + run.group(1).toLowerCase(Locale.ROOT) + "/results";
        try {
            var response = Jsoup.connect(endpoint).followRedirects(false).ignoreHttpErrors(true).timeout(20_000).maxBodySize(MAX_BYTES + 1)
                    .header("Accept", "text/html").header("Accept-Language", "es").cookie("_facom_key", cookie).execute();
            if (response.statusCode() == 302 || response.statusCode() == 303 || response.statusCode() == 401 || response.statusCode() == 403) {
                throw invalid("SOURCE_AUTH_REQUIRED");
            }
            if (response.statusCode() != 200 || response.contentType() == null || !response.contentType().startsWith("text/html")) { throw invalid("SOURCE_UNAVAILABLE"); }
            byte[] bytes = response.bodyAsBytes();
            if (bytes.length > MAX_BYTES) { throw invalid("EMPTY_OR_TOO_LARGE"); }
            String html = new String(bytes, StandardCharsets.UTF_8);
            if (!Jsoup.parse(html).select("form[action='/user/login']").isEmpty()) { throw invalid("SOURCE_AUTH_REQUIRED"); }
            return html;
        } catch (java.io.IOException failure) { throw invalid("SOURCE_UNAVAILABLE"); }
    }
    String sessionCookie() {
        String club = TenantContext.require();
        if (sessions.isBlank() || !club.matches("[A-Za-z0-9_-]{1,100}")) { throw invalid("SOURCE_AUTH_REQUIRED"); }
        try {
            var configured = new ObjectMapper().readTree(sessions);
            var secret = configured == null ? null : configured.get(club);
            if (secret == null || !secret.isTextual()) { throw invalid("SOURCE_AUTH_REQUIRED"); }
            String value = secret.textValue();
            if (!value.matches("[A-Za-z0-9_.~+%=-]{20,8192}")) { throw invalid("SOURCE_AUTH_REQUIRED"); }
            return value;
        } catch (java.io.IOException failure) { throw invalid("SOURCE_AUTH_REQUIRED"); }
    }
}
