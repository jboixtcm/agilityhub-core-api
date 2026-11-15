package com.agilityhub.core.clubs.statistics.application;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

@Component
public class FcagSourceClient {
    public String fetch(String sourceUrl) {
        String safe = url(CompetitionModels.Source.FCAG, sourceUrl);
        try {
            // Jsoup bounds the complete request and response size, including a slowly streamed body.
            var response = Jsoup.connect(safe).followRedirects(false).timeout(20_000).maxBodySize(MAX_BYTES + 1)
                    .header("Accept", "text/html").userAgent("CompetitionResults/1.0").execute();
            if (response.statusCode() != 200 || response.contentType() == null || !response.contentType().startsWith("text/html")) {
                throw invalid("SOURCE_UNAVAILABLE");
            }
            byte[] bytes = response.bodyAsBytes();
            if (bytes.length > MAX_BYTES) { throw invalid("EMPTY_OR_TOO_LARGE"); }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw invalid("SOURCE_UNAVAILABLE"); }
    }
}
