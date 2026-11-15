package com.agilityhub.core.clubs.followup.application;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.support.TestPictures.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): the dog photo's rendition. Every picture is drawn here (never a real photo); the EXIF
 * blocks are written by hand with a fictional camera name and fictional GPS coordinates.
 */
class ThumbnailRendererTest {
    static final ThumbnailRenderer.Job JOB = new ThumbnailRenderer.Job();

    @ParameterizedTest
    @CsvSource({"image/jpeg,640,480,256,192", "image/jpeg,480,640,192,256", "image/png,800,600,256,192", "image/png,300,1200,64,256",
            "image/jpeg,120,90,120,90", "image/png,100,80,100,80"})
    void WEB_05_landscapeAndPortraitJpegAndPngAreReducedToAJpegOf256OnTheLongestSideNeverEnlarged(String type, int width, int height, int expectedWidth, int expectedHeight) throws Exception {
        var picture = quadrants(width, height);
        byte[] original = type.equals("image/jpeg") ? jpeg(picture, false) : png(picture);
        var thumbnail = decode(ThumbnailRenderer.render(type, original, JOB));
        assertThat(thumbnail.getWidth()).isEqualTo(expectedWidth);
        assertThat(thumbnail.getHeight()).isEqualTo(expectedHeight);
        assertQuadrants(thumbnail);
    }

    @Test void WEB_05_aTransparentPngIsReducedOnWhite() throws Exception {
        var picture = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        var graphics = picture.createGraphics(); graphics.setColor(Color.RED); graphics.fillRect(0, 0, 300, 400); graphics.dispose();
        var thumbnail = decode(ThumbnailRenderer.render("image/png", png(picture), JOB));
        assertThat(colour(thumbnail, 40, 85)).isEqualTo("red");
        assertThat(colour(thumbnail, 210, 85)).isEqualTo("white");
    }

    /**
     * EXIF 2.3 tag 0x0112, the table of the standard: where the stored image's row 0 and column 0 are shown. The stored picture has a
     * red band along its row 0 and a blue one along its column 0; the rendition must show them on those sides.
     */
    @ParameterizedTest
    @CsvSource({"1,top,left", "2,top,right", "3,bottom,right", "4,bottom,left", "5,left,top", "6,right,top", "7,right,bottom", "8,left,bottom"})
    void WEB_05_aJpegsExifOrientationIsAppliedSoTheThumbnailIsUpright(int orientation, String rowZero, String columnZero) throws Exception {
        var stored = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = stored.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 640, 480);
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, 640, 60);
        graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 60, 480);
        graphics.dispose();
        for (boolean little : new boolean[] {false, true}) {
            var thumbnail = decode(ThumbnailRenderer.render("image/jpeg", withExif(jpeg(stored, false), orientation, little), JOB));
            boolean swapped = orientation >= 5;
            assertThat(thumbnail.getWidth()).isEqualTo(swapped ? 192 : 256);
            assertThat(thumbnail.getHeight()).isEqualTo(swapped ? 256 : 192);
            assertThat(side(thumbnail, rowZero)).as("row 0, orientation " + orientation).isEqualTo("red");
            assertThat(side(thumbnail, columnZero)).as("column 0, orientation " + orientation).isEqualTo("blue");
            assertThat(side(thumbnail, opposite(rowZero))).isEqualTo("white");
            assertThat(side(thumbnail, opposite(columnZero))).isEqualTo("white");
        }
    }

    @Test void WEB_05_theRenditionCarriesNoMetadataOfTheOriginal() throws Exception {
        byte[] original = withExif(jpeg(quadrants(800, 600), false), 1, false);
        assertThat(contains(original, "FictionalCam")).isTrue();
        byte[] thumbnail = ThumbnailRenderer.render("image/jpeg", original, JOB);
        assertThat(contains(thumbnail, "FictionalCam")).isFalse();
        assertThat(contains(thumbnail, "Exif")).isFalse();
        assertThat(markers(thumbnail)).doesNotContain(0xE1, 0xE2, 0xED, 0xFE).contains(0xE0, 0xC0, 0xDA);
        byte[] fromPng = ThumbnailRenderer.render("image/png", withPngText(png(quadrants(400, 300)), "tEXt", "Author\0Fictional Author"), JOB);
        assertThat(contains(fromPng, "Fictional Author")).isFalse();
        assertThat(markers(fromPng)).doesNotContain(0xE1, 0xE2, 0xED, 0xFE);
    }

    @Test void WEB_05_anOversizedHeaderIsRefusedBeforeAnyDecoding() throws Exception {
        // A tiny file that claims 50,000 × 50,000 pixels (2.5 gigapixels): refused from its header, never decoded.
        assertRefused("image/png", pngClaiming(50_000, 50_000), "TOO_LARGE");
        assertRefused("image/png", pngClaiming(12_001, 100), "TOO_LARGE");
        assertRefused("image/png", pngClaiming(8_000, 7_000), "TOO_LARGE");
        assertRefused("image/jpeg", jpegClaiming(jpeg(quadrants(16, 16), false), 13_000, 16), "TOO_LARGE");
        assertRefused("image/jpeg", jpegClaiming(jpeg(quadrants(16, 16), false), 9_000, 9_000), "TOO_LARGE");
        // A progressive JPEG keeps the whole image in its decoder: a lower bound (4 MP).
        assertRefused("image/jpeg", jpeg(quadrants(2_100, 2_000), true), "TOO_LARGE");
        assertThat(decode(ThumbnailRenderer.render("image/jpeg", jpeg(quadrants(1_000, 800), true), JOB)).getWidth()).isEqualTo(256);
    }

    @Test void WEB_05_aTruncatedOrDamagedFileIsRefused() throws Exception {
        byte[] jpeg = jpeg(quadrants(640, 480), false), png = png(quadrants(640, 480));
        assertRefused("image/jpeg", Arrays.copyOf(jpeg, jpeg.length / 2), "DAMAGED");
        assertRefused("image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2), "DAMAGED");
        assertRefused("image/png", Arrays.copyOf(png, png.length / 2), "DAMAGED");
        assertRefused("image/png", Arrays.copyOf(png, png.length - 12), "DAMAGED");
        // Complete markers, damaged entropy-coded data: the decoder's warning refuses it.
        byte[] damaged = jpeg.clone();
        int scan = indexOf(damaged, new byte[] {(byte) 0xFF, (byte) 0xDA});
        Arrays.fill(damaged, scan + 40, damaged.length - 400, (byte) 0x00);
        assertRefused("image/jpeg", damaged, "DAMAGED");
        assertRefused("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8}, "MALFORMED");
        assertRefused("image/jpeg", new byte[0], "MALFORMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/webp", "image/heic", "image/gif", "image/svg+xml", "application/pdf", "image/x-unknown"})
    void WEB_05_aWebpOrAnyOtherTypeGetsNoRendition(String type) throws Exception {
        // A WebP container header ("RIFF....WEBPVP8 "), fictional payload.
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 fictional".getBytes(StandardCharsets.ISO_8859_1);
        assertRefused(type, webp, "UNSUPPORTED_TYPE");
        assertRefused(type, jpeg(quadrants(64, 48), false), "UNSUPPORTED_TYPE");
    }

    @Test void WEB_05_aFileWhoseContentIsNotItsTypeIsRefused() throws Exception {
        assertRefused("image/png", jpeg(quadrants(64, 48), false), "MALFORMED");
        assertRefused("image/jpeg", png(quadrants(64, 48)), "MALFORMED");
        assertRefused("image/png", "RIFF\0\0\0\0WEBPVP8 fictional-padding-to-33-bytes".getBytes(StandardCharsets.ISO_8859_1), "MALFORMED");
    }

    @Test void WEB_05_anOrientationThatCannotBeAppliedWithCertaintyGetsNoRendition() throws Exception {
        byte[] jpeg = jpeg(quadrants(64, 48), false);
        assertRefused("image/jpeg", withExif(jpeg, 9, false), "ORIENTATION");
        assertRefused("image/jpeg", withExif(withExif(jpeg, 1, false), 6, false), "ORIENTATION");
        byte[] broken = withExif(jpeg, 6, false);
        int tiff = indexOf(broken, "MM\0*".getBytes(StandardCharsets.ISO_8859_1));
        broken[tiff + 7] = (byte) 0xF0; // IFD0 beyond the segment
        assertRefused("image/jpeg", broken, "ORIENTATION");
        // A PNG's eXIf orientation is not applied (browsers disagree): only the default one is accepted.
        assertRefused("image/png", withPngText(png(quadrants(64, 48)), "eXIf", new String(tiff(6, false), StandardCharsets.ISO_8859_1)), "ORIENTATION");
        assertThat(ThumbnailRenderer.render("image/png", withPngText(png(quadrants(64, 48)), "eXIf", new String(tiff(1, true), StandardCharsets.ISO_8859_1)), JOB)).isNotEmpty();
    }

    @Test void WEB_05_aPngWithCompressedTextIsRefusedAndPlainTextIsNot() throws Exception {
        byte[] png = png(quadrants(64, 48));
        assertRefused("image/png", withPngText(png, "zTXt", "Comment\0\0x"), "COMPRESSED_TEXT");
        assertRefused("image/png", withPngText(png, "iTXt", "Comment\0\1\0\0\0x"), "COMPRESSED_TEXT");
        assertThat(ThumbnailRenderer.render("image/png", withPngText(png, "iTXt", "Comment\0\0\0\0\0plain"), JOB)).isNotEmpty();
    }

    @Test void WEB_05_aCancelledJobDecodesNothing() throws Exception {
        var cancelled = new ThumbnailRenderer.Job(); cancelled.cancelled = true;
        assertThatThrownBy(() -> ThumbnailRenderer.render("image/jpeg", jpeg(quadrants(640, 480), false), cancelled))
                .isInstanceOfSatisfying(ThumbnailRenderer.Refused.class, refused -> assertThat(refused.reason).isEqualTo("TIMEOUT"));
    }

    // ---- assertions and crafted headers (the pictures: TestPictures)

    static void assertQuadrants(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        assertThat(colour(image, w / 4, h / 4)).isEqualTo("red");
        assertThat(colour(image, 3 * w / 4, h / 4)).isEqualTo("green");
        assertThat(colour(image, w / 4, 3 * h / 4)).isEqualTo("blue");
        assertThat(colour(image, 3 * w / 4, 3 * h / 4)).isEqualTo("yellow");
    }
    static BufferedImage decode(byte[] jpeg) throws IOException {
        assertThat(jpeg[0] & 0xff).isEqualTo(0xFF); assertThat(jpeg[1] & 0xff).isEqualTo(0xD8);
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }
    static void assertRefused(String type, byte[] bytes, String reason) {
        assertThatThrownBy(() -> ThumbnailRenderer.render(type, bytes, JOB))
                .isInstanceOfSatisfying(ThumbnailRenderer.Refused.class, refused -> assertThat(refused.reason).isEqualTo(reason));
    }
    /** A complete little PNG whose IHDR claims {@code width} × {@code height}. */
    static byte[] pngClaiming(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        out.write(chunk("IHDR", header.array()));
        out.write(chunk("IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01}));
        out.write(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }
    /** The JPEG with its SOF0 claiming {@code width} × {@code height}. */
    static byte[] jpegClaiming(byte[] jpeg, int width, int height) {
        byte[] copy = jpeg.clone();
        int sof = indexOf(copy, new byte[] {(byte) 0xFF, (byte) 0xC0});
        copy[sof + 5] = (byte) (height >> 8); copy[sof + 6] = (byte) height; copy[sof + 7] = (byte) (width >> 8); copy[sof + 8] = (byte) width;
        return copy;
    }
}
