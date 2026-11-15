package com.agilityhub.core.clubs.followup.application;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStreamImpl;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): the small rendition of a dog photo that the lists show instead of the original, made
 * with the JDK alone (no image library: the build is offline). Only JPEG and PNG, the two formats the JDK decodes; anything else,
 * and any picture this class cannot read with certainty, gets no rendition ({@link Refused}, a fixed reason code).
 *
 * <p>Bounded before any pixel is decoded: the header is read first (the JPEG markers, the PNG chunks) and the picture is refused
 * over {@link #MAX_SIDE} on a side or {@link #MAX_PIXELS} pixels; a JPEG that its decoder (libjpeg in the JDK) must hold whole —
 * progressive, or in more than one scan, which keeps every DCT coefficient of the image, up to 6 bytes a pixel — over
 * {@link #MAX_BUFFERED_PIXELS}. It is then decoded subsampled ({@code ImageReadParam} source subsampling), so the decoded raster's
 * longest side is at most twice {@link #EDGE} (≤ 512 px, ≤ 2 MB), whatever the original. Measured on this JDK: a 50 MP sequential
 * JPEG or PNG decodes subsampled in about 0.1 s with a few MB of heap; a 50 MP progressive JPEG takes 1.3 s and about 150 MB of
 * native memory, hence its 4 MP bound (≤ 24 MB). A decoder warning (a truncated or corrupt file) refuses the picture.
 *
 * <p>The rendition is upright: a JPEG's EXIF orientation (the first and only `Exif` APP1 segment, IFD0 tag 0x0112) is applied
 * to it; a malformed EXIF block, or several, refuses the picture. A PNG with an `eXIf` chunk whose orientation is not the default
 * one is refused (browsers do not agree on it). The rendition is a new baseline JPEG written from pixels only: no EXIF, GPS, ICC
 * profile, comment or other metadata of the original.
 */
final class ThumbnailRenderer {
    /** The rendition's longest side, never larger than the original's. */
    static final int EDGE = 256;
    static final int MAX_SIDE = 12_000;
    static final long MAX_PIXELS = 50_000_000L;
    static final long MAX_BUFFERED_PIXELS = 4_000_000L;
    static final float QUALITY = 0.82f;
    /** The stored photo types that get a rendition. */
    static final Set<String> TYPES = Set.of("image/jpeg", "image/png");

    private ThumbnailRenderer() { }

    /** Why no rendition was made: a fixed code, never personal data (it is all that is logged). */
    static final class Refused extends Exception {
        final String reason;
        Refused(String reason) { super(reason, null, false, false); this.reason = reason; }
    }
    /** Set by the waiting caller when it stops waiting; the decoder's progress callback then aborts the read. */
    static final class Job { volatile boolean cancelled; }
    record Header(int width, int height, int orientation, boolean buffered) { }

    static byte[] render(String mimeType, byte[] bytes, Job job) throws Refused {
        Objects.requireNonNull(bytes);
        var header = switch (mimeType) {
            case "image/jpeg" -> jpeg(bytes);
            case "image/png" -> png(bytes);
            default -> throw new Refused("UNSUPPORTED_TYPE");
        };
        long pixels = (long) header.width() * header.height();
        if (header.width() > MAX_SIDE || header.height() > MAX_SIDE || pixels > (header.buffered() ? MAX_BUFFERED_PIXELS : MAX_PIXELS)) {
            throw new Refused("TOO_LARGE");
        }
        var reader = jdkReader(mimeType.equals("image/jpeg") ? "jpeg" : "png");
        try (var input = new Bytes(bytes)) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width != header.width() || height != header.height()) { throw new Refused("MALFORMED"); }
            var warned = new AtomicBoolean(); var aborted = new AtomicBoolean();
            reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
            reader.addIIOReadProgressListener(new Progress(job, aborted));
            int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (2.0 * EDGE)));
            var param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            if (job.cancelled) { throw new Refused("TIMEOUT"); }
            BufferedImage decoded = reader.read(0, param);
            if (job.cancelled || aborted.get()) { throw new Refused("TIMEOUT"); }
            if (warned.get()) { throw new Refused("DAMAGED"); }
            return encode(orient(scale(decoded, width, height), header.orientation()));
        } catch (IOException | RuntimeException failure) {
            throw new Refused("UNDECODABLE");
        } finally {
            reader.dispose();
        }
    }

    // ---- headers (read before any decoding)

    /** The JPEG markers up to EOI: the frame (SOF0, SOF1 or SOF2 only), the scans and the EXIF orientation. */
    static Header jpeg(byte[] b) throws Refused {
        if (b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) { throw new Refused("MALFORMED"); }
        int pos = 2, width = -1, height = -1, scans = 0, orientation = 1, exif = 0;
        boolean progressive = false, ended = false;
        while (pos < b.length) {
            if ((b[pos] & 0xff) != 0xFF) { throw new Refused("MALFORMED"); }
            while (pos < b.length && (b[pos] & 0xff) == 0xFF) { pos++; }
            if (pos >= b.length) { break; }
            int marker = b[pos++] & 0xff;
            if (marker == 0xD9) { ended = true; break; }
            if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) { continue; }
            if (marker == 0x00 || marker == 0xD8 || pos + 2 > b.length) { throw new Refused("MALFORMED"); }
            int length = u16(b, pos, false);
            if (length < 2 || pos + length > b.length) { throw new Refused(pos + length > b.length ? "DAMAGED" : "MALFORMED"); }
            int start = pos + 2, end = pos + length;
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (width >= 0) { throw new Refused("MALFORMED"); }
                if (marker != 0xC0 && marker != 0xC1 && marker != 0xC2) { throw new Refused("UNSUPPORTED_TYPE"); }
                if (length < 8) { throw new Refused("MALFORMED"); }
                progressive = marker == 0xC2; height = u16(b, start + 1, false); width = u16(b, start + 3, false);
            } else if (marker == 0xE1 && end - start >= 6 && ascii(b, start, 6).equals("Exif\0\0")) {
                if (++exif > 1) { throw new Refused("ORIENTATION"); }
                orientation = orientation(b, start + 6, end);
            } else if (marker == 0xDA) {
                if (width < 0) { throw new Refused("MALFORMED"); }
                scans++;
                pos = entropyEnd(b, end);
                continue;
            }
            pos = end;
        }
        if (!ended) { throw new Refused("DAMAGED"); }
        if (width <= 0 || height <= 0 || scans == 0) { throw new Refused("MALFORMED"); }
        return new Header(width, height, orientation, progressive || scans > 1);
    }
    /** The first marker after a scan's entropy-coded data (a stuffed `FF 00` or a restart marker is part of the data). */
    private static int entropyEnd(byte[] b, int pos) {
        while (pos < b.length) {
            if ((b[pos] & 0xff) != 0xFF) { pos++; continue; }
            int next = pos + 1;
            while (next < b.length && (b[next] & 0xff) == 0xFF) { next++; }
            if (next >= b.length) { return b.length; }
            int marker = b[next] & 0xff;
            if (marker == 0x00 || marker >= 0xD0 && marker <= 0xD7) { pos = next + 1; continue; }
            return pos;
        }
        return pos;
    }
    /** The PNG chunks up to IEND: IHDR's size, an `eXIf` orientation, and no compressed text (which a decoder could inflate). */
    static Header png(byte[] b) throws Refused {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (b.length < 33) { throw new Refused("MALFORMED"); }
        for (int i = 0; i < 8; i++) { if (b[i] != signature[i]) { throw new Refused("MALFORMED"); } }
        if (u32(b, 8, false) != 13 || !ascii(b, 12, 4).equals("IHDR")) { throw new Refused("MALFORMED"); }
        long width = u32(b, 16, false), height = u32(b, 20, false);
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) { throw new Refused("MALFORMED"); }
        int pos = 8, orientation = 1, exif = 0; boolean ended = false;
        while (pos + 12 <= b.length) {
            long length = u32(b, pos, false); String type = ascii(b, pos + 4, 4);
            if (length > b.length - pos - 12L) { throw new Refused("DAMAGED"); }
            int data = pos + 8, end = data + (int) length;
            switch (type) {
                case "IEND" -> ended = true;
                case "zTXt" -> throw new Refused("COMPRESSED_TEXT");
                case "iTXt" -> {
                    int zero = data; while (zero < end && b[zero] != 0) { zero++; }
                    if (zero + 1 >= end || b[zero + 1] != 0) { throw new Refused("COMPRESSED_TEXT"); }
                }
                case "eXIf" -> {
                    if (++exif > 1) { throw new Refused("ORIENTATION"); }
                    orientation = orientation(b, data, end);
                }
                default -> { }
            }
            if (ended) { break; }
            pos = end + 4;
        }
        if (!ended) { throw new Refused("DAMAGED"); }
        if (orientation != 1) { throw new Refused("ORIENTATION"); }
        return new Header((int) width, (int) height, 1, false);
    }
    /** The orientation tag (0x0112, one SHORT) of a TIFF block's IFD0: 1 when absent; anything malformed refuses the picture. */
    static int orientation(byte[] b, int tiff, int end) throws Refused {
        if (end - tiff < 8) { throw new Refused("ORIENTATION"); }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') { little = true; } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') { little = false; } else { throw new Refused("ORIENTATION"); }
        if (u16(b, tiff + 2, little) != 42) { throw new Refused("ORIENTATION"); }
        long ifd = u32(b, tiff + 4, little);
        if (ifd < 8 || ifd > end - tiff - 2L) { throw new Refused("ORIENTATION"); }
        int entries = tiff + (int) ifd, count = u16(b, entries, little);
        if (entries + 2L + 12L * count > end) { throw new Refused("ORIENTATION"); }
        int orientation = 1; boolean found = false;
        for (int i = 0; i < count; i++) {
            int entry = entries + 2 + 12 * i;
            if (u16(b, entry, little) != 0x0112) { continue; }
            if (found || u16(b, entry + 2, little) != 3 || u32(b, entry + 4, little) != 1) { throw new Refused("ORIENTATION"); }
            orientation = u16(b, entry + 8, little); found = true;
        }
        if (orientation < 1 || orientation > 8) { throw new Refused("ORIENTATION"); }
        return orientation;
    }
    private static int u16(byte[] b, int at, boolean little) {
        return little ? (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 : (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }
    private static long u32(byte[] b, int at, boolean little) {
        return little ? (u16(b, at, true) | (long) u16(b, at + 2, true) << 16) : ((long) u16(b, at, false) << 16 | u16(b, at + 2, false));
    }
    private static String ascii(byte[] b, int at, int length) { return new String(b, at, length, StandardCharsets.ISO_8859_1); }

    // ---- pixels

    /** To at most {@link #EDGE} on the longest side (never enlarged), on white (a transparent PNG), as plain RGB. */
    static BufferedImage scale(BufferedImage decoded, int width, int height) {
        int longest = Math.max(width, height);
        int targetWidth = longest <= EDGE ? width : Math.max(1, (int) Math.round(width * (double) EDGE / longest));
        int targetHeight = longest <= EDGE ? height : Math.max(1, (int) Math.round(height * (double) EDGE / longest));
        var result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
        } finally { graphics.dispose(); }
        return result;
    }
    /** The EXIF orientation applied (TIFF 6.0 / EXIF 2.3 tag 0x0112): each pixel of the upright image read from the stored one. */
    static BufferedImage orient(BufferedImage stored, int orientation) {
        if (orientation == 1) { return stored; }
        int w = stored.getWidth(), h = stored.getHeight();
        boolean swapped = orientation >= 5;
        var upright = new BufferedImage(swapped ? h : w, swapped ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < upright.getHeight(); y++) {
            for (int x = 0; x < upright.getWidth(); x++) {
                int sx, sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
                    default -> { sx = w - 1 - y; sy = x; }
                }
                upright.setRGB(x, y, stored.getRGB(sx, sy));
            }
        }
        return upright;
    }
    /** A baseline JPEG of the pixels only (the writer's own JFIF header, no metadata of the original). */
    static byte[] encode(BufferedImage image) throws IOException, Refused {
        var writer = jdkWriter();
        try {
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(QUALITY);
            var out = new ByteArrayOutputStream();
            try (var stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally { writer.dispose(); }
    }

    /** The JDK's own decoder (`com.sun.imageio.plugins`), never one a dependency may register. */
    private static ImageReader jdkReader(String format) throws Refused {
        for (var readers = ImageIO.getImageReadersByFormatName(format); readers.hasNext(); ) {
            var reader = readers.next();
            if (reader.getClass().getName().startsWith("com.sun.imageio.plugins.")) { return reader; }
            reader.dispose();
        }
        throw new Refused("NO_DECODER");
    }
    private static ImageWriter jdkWriter() throws Refused {
        for (var writers = ImageIO.getImageWritersByFormatName("jpeg"); writers.hasNext(); ) {
            var writer = writers.next();
            if (writer.getClass().getName().startsWith("com.sun.imageio.plugins.")) { return writer; }
            writer.dispose();
        }
        throw new Refused("NO_DECODER");
    }
    /** Aborts the read, from the decoding thread itself, once the caller has stopped waiting. */
    private record Progress(Job job, AtomicBoolean aborted) implements IIOReadProgressListener {
        @Override public void imageProgress(ImageReader source, float percentageDone) { if (job.cancelled) { aborted.set(true); source.abort(); } }
        @Override public void readAborted(ImageReader source) { aborted.set(true); }
        @Override public void sequenceStarted(ImageReader source, int minIndex) { }
        @Override public void sequenceComplete(ImageReader source) { }
        @Override public void imageStarted(ImageReader source, int imageIndex) { }
        @Override public void imageComplete(ImageReader source) { }
        @Override public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) { }
        @Override public void thumbnailProgress(ImageReader source, float percentageDone) { }
        @Override public void thumbnailComplete(ImageReader source) { }
    }
    /** The photo's bytes as an image stream, read in place (no copy, no cache file). */
    private static final class Bytes extends ImageInputStreamImpl {
        private final byte[] bytes;
        Bytes(byte[] bytes) { this.bytes = bytes; }
        @Override public int read() {
            bitOffset = 0;
            return streamPos < bytes.length ? bytes[(int) streamPos++] & 0xff : -1;
        }
        @Override public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            bitOffset = 0;
            if (length == 0) { return 0; }
            if (streamPos >= bytes.length) { return -1; }
            int count = (int) Math.min(length, bytes.length - streamPos);
            System.arraycopy(bytes, (int) streamPos, target, offset, count);
            streamPos += count;
            return count;
        }
        @Override public long length() { return bytes.length; }
    }
}
