package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.shared.application.ContentSecurityPolicies;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.*;

/** INC-43 (E11-T02; review of E5-T26 #3 and #6, CONVENCIONS_API §5): the local downloads' fallbacks and their HTTP `Range` answers. */
class AttachmentDownloadsTest {
    static final byte[] BYTES = "0123456789".getBytes(StandardCharsets.US_ASCII);

    static ResponseEntity<InputStreamResource> answer(String storedType, String... requestHeaders) throws IOException {
        var request = new HttpHeaders();
        for (int i = 0; i < requestHeaders.length; i += 2) { request.add(requestHeaders[i], requestHeaders[i + 1]); }
        return AttachmentsController.download(new AttachmentService.Download("example.bin", storedType, BYTES.length, new ByteArrayInputStream(BYTES)), request);
    }
    static String body(ResponseEntity<InputStreamResource> response) throws IOException {
        return new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }

    @Test void INC_43_CONVENCIONS_API_5_aStoredTypeThatDoesNotParseAnswersAnOctetStreamAttachment() throws IOException {
        var response = answer("not a type");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(body(response)).isEqualTo("0123456789");
    }

    @Test void INC_43_CONVENCIONS_API_5_theImageWildcardIsAnAttachment() throws IOException {
        var response = answer("image/*");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(answer("image/webp").getHeaders().getContentDisposition().isInline()).isTrue();
    }

    @Test void INC_43_aWholeDownloadAdvertisesByteRanges() throws IOException {
        var response = answer("video/mp4");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(body(response)).isEqualTo("0123456789");
    }

    @Test void INC_43_aSingleRangeAnswersThatPartWithTheDownloadsHeaders() throws IOException {
        var response = answer("video/mp4", HttpHeaders.RANGE, "bytes=2-5");
        assertThat(response.getStatusCode().value()).isEqualTo(206);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("video/mp4"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(ContentSecurityPolicies.DOWNLOAD);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("example.bin");
        assertThat(body(response)).isEqualTo("2345");
        // An open end, a suffix and an end past the file.
        assertThat(body(answer("video/mp4", HttpHeaders.RANGE, "bytes=7-"))).isEqualTo("789");
        assertThat(answer("video/mp4", HttpHeaders.RANGE, "bytes=-3").getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10");
        assertThat(body(answer("video/mp4", HttpHeaders.RANGE, "bytes=-30"))).isEqualTo("0123456789");
        var past = answer("video/mp4", HttpHeaders.RANGE, "bytes=8-100");
        assertThat(past.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 8-9/10");
        assertThat(body(past)).isEqualTo("89");
    }

    @Test void INC_43_aRangeBeyondTheFileAnswers416WithItsLength() throws IOException {
        for (String range : new String[] {"bytes=10-", "bytes=10-12", "bytes=-0"}) {
            var response = answer("video/mp4", HttpHeaders.RANGE, range);
            assertThat(response.getStatusCode().value()).as(range).isEqualTo(416);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).as(range).isEqualTo("bytes */10");
            assertThat(response.getBody()).as(range).isNull();
        }
    }

    /** RFC 9110 §14.2: a server may ignore `Range`. Several ranges, a malformed one or an `If-Range` (no validators here) answer the whole file. */
    @Test void INC_43_severalRangesAMalformedOneOrAnIfRangeAnswerTheWholeFile() throws IOException {
        for (String[] request : new String[][] {{HttpHeaders.RANGE, "bytes=0-1,4-5"}, {HttpHeaders.RANGE, "items=0-1"}, {HttpHeaders.RANGE, "bytes=5-2"},
                {HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, "\"an-etag\""}}) {
            var response = answer("video/mp4", request);
            assertThat(response.getStatusCode().value()).as(String.join(" ", request)).isEqualTo(200);
            assertThat(body(response)).isEqualTo("0123456789");
        }
    }
}
