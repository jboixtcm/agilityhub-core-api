package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-53 point 2 (E11-T02; CONVENCIONS_API §5): a URL that the local stack answers as a path of the api (a signed upload or
 * download, an export's status) is a `uri-reference` in the contract, never a `uri`, which only an absolute URL satisfies.
 */
class UriReferenceContractTest {
    @Test void INC_53_CONVENCIONS_API_5_theUrlsTheLocalStackAnswersAsPathsAreUriReferences() throws Exception {
        var schemas = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()).path("components").path("schemas");
        for (String property : List.of("UploadUrl/uploadUrl", "Upload/uploadUrl", "Attachment/url", "SignupDocumentFile/downloadUrl", "RemittanceFile/downloadUrl",
                "ExportAccepted/statusUrl")) {
            String[] part = property.split("/");
            assertThat(schemas.path(part[0]).path("properties").path(part[1]).path("format").asText()).as(property).isEqualTo("uri-reference");
        }
    }
}
