package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.support.TestPictures;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): the attendance sheet (21, and D12's attendance panel of the instructor agenda, which
 * reads the same `GET /class-sessions/{id}/attendance`) signs the photo's small rendition beside the photo: `dogPhotoThumbnailUrl`
 * for the row, `dogPhotoUrl` for the enlarged photo; both null for a dog without a photo. The photo is drawn in the test.
 */
class AttendanceThumbnailIT extends BookingFixtures {
    @Test void WEB_05_T_10_27_eachSheetRowCarriesTheRenditionBesideThePhoto() throws Exception {
        book(as("laura"), "wed", "s08-d-duna"); book(as("joan"), "wed", "s08-d-toby");
        byte[] original = TestPictures.jpeg(TestPictures.quadrants(1024, 768), false);
        var grant = call(HttpMethod.POST, "/attachments/upload-url", Map.of("purpose", "DOG_PHOTO", "fileName", "duna", "mimeType", "image/jpeg", "sizeBytes", original.length), as("admin"), 201);
        mvc.perform(put(grant.path("uploadUrl").asText()).header("Host", HOST).contentType("image/jpeg").content(original)).andReturn();
        call(HttpMethod.PUT, "/dogs/s08-d-duna/photo", Map.of("fileKey", grant.path("fileKey").asText()), as("admin"), 200);
        assertThat(mongo.findById("s08-d-duna", Document.class, "dogs").getString("photoThumbnailFileKey")).isNotBlank();

        for (var reader : new String[] {"inst", "admin"}) {
            var rows = call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as(reader), 200).path("rows");
            var duna = rows.findParents("dogId").stream().filter(row -> row.path("dogId").asText().equals("s08-d-duna")).findFirst().orElseThrow();
            var toby = rows.findParents("dogId").stream().filter(row -> row.path("dogId").asText().equals("s08-d-toby")).findFirst().orElseThrow();
            assertThat(signed(duna.path("dogPhotoUrl").asText())).isEqualTo(original);
            var thumbnail = ImageIO.read(new ByteArrayInputStream(signed(duna.path("dogPhotoThumbnailUrl").asText())));
            assertThat(thumbnail.getWidth()).isEqualTo(256); assertThat(thumbnail.getHeight()).isEqualTo(192);
            assertThat(TestPictures.colour(thumbnail, 64, 48)).isEqualTo("red");
            assertThat(toby.path("dogPhotoUrl").isMissingNode() || toby.path("dogPhotoUrl").isNull()).isTrue();
            assertThat(toby.path("dogPhotoThumbnailUrl").isMissingNode() || toby.path("dogPhotoThumbnailUrl").isNull()).isTrue();
            assertThat(rows.toString()).doesNotContain("photoThumbnailFileKey", "photoFileKey");
        }
        // A member never reads the sheet, whatever it carries (unchanged, S10 §6).
        call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("laura"), 403);
    }

    byte[] signed(String url) throws Exception {
        var response = mvc.perform(get(url).header("Host", HOST)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsByteArray();
    }
}
