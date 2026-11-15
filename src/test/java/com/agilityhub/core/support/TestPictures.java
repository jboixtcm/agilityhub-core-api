package com.agilityhub.core.support;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * WEB-05: pictures drawn in the test (never a real photo) and their files: JPEG and PNG, a camera-like EXIF block written by hand
 * (the fictional camera «FictionalCam», fictional GPS coordinates) and extra PNG chunks.
 */
public final class TestPictures {
    private TestPictures() { }

    /** Red, green, blue and yellow quadrants (top left, top right, bottom left, bottom right). */
    public static BufferedImage quadrants(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.setColor(Color.GREEN); graphics.fillRect(width / 2, 0, width - width / 2, height / 2);
        graphics.setColor(Color.BLUE); graphics.fillRect(0, height / 2, width / 2, height - height / 2);
        graphics.setColor(Color.YELLOW); graphics.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
        graphics.dispose();
        return image;
    }
    public static String colour(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y), r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        if (r > 200 && g > 200 && b > 200) { return "white"; }
        if (r > 180 && g > 180 && b < 90) { return "yellow"; }
        if (r > 160 && g < 90 && b < 90) { return "red"; }
        if (g > 160 && r < 90 && b < 90) { return "green"; }
        if (b > 160 && r < 90 && g < 90) { return "blue"; }
        return "other(" + r + "," + g + "," + b + ")";
    }
    public static String side(BufferedImage image, String side) {
        int w = image.getWidth(), h = image.getHeight();
        return switch (side) {
            case "top" -> colour(image, w / 2, 3);
            case "bottom" -> colour(image, w / 2, h - 4);
            case "left" -> colour(image, 3, h / 2);
            default -> colour(image, w - 4, h / 2);
        };
    }
    public static String opposite(String side) {
        return switch (side) { case "top" -> "bottom"; case "bottom" -> "top"; case "left" -> "right"; default -> "left"; };
    }
    public static byte[] jpeg(BufferedImage image, boolean progressive) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        if (progressive) { param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT); }
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) { writer.setOutput(stream); writer.write(null, new IIOImage(image, null, null), param); }
        finally { writer.dispose(); }
        return out.toByteArray();
    }
    public static byte[] png(BufferedImage image) throws IOException { var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray(); }
    /**
     * A camera-like JPEG: the writer's JFIF APP0 replaced by an `Exif` APP1 right after SOI, whose IFD0 holds the orientation, the
     * fictional camera «FictionalCam» and a GPS IFD with fictional coordinates.
     */
    public static byte[] withExif(byte[] jpeg, int orientation, boolean little) throws IOException {
        int start = 2;
        if ((jpeg[2] & 0xff) == 0xFF && (jpeg[3] & 0xff) == 0xE0) { start = 4 + ((jpeg[4] & 0xff) << 8 | jpeg[5] & 0xff); }
        byte[] tiff = tiff(orientation, little);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF); out.write(0xE1); int length = 2 + 6 + tiff.length; out.write(length >> 8); out.write(length & 0xff);
        out.write("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)); out.write(tiff);
        out.write(jpeg, start, jpeg.length - start);
        return out.toByteArray();
    }
    /** A TIFF block: IFD0 {Make, Orientation, GPS IFD pointer}, then the GPS IFD {GPSLatitudeRef «N»} and the Make text. */
    public static byte[] tiff(int orientation, boolean little) {
        var buffer = java.nio.ByteBuffer.allocate(128).order(little ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
        buffer.put((byte) (little ? 'I' : 'M')).put((byte) (little ? 'I' : 'M')).putShort((short) 42).putInt(8);
        buffer.putShort((short) 3);
        buffer.putShort((short) 0x010F).putShort((short) 2).putInt(13).putInt(68);                 // Make → offset 68
        buffer.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        buffer.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(50);                  // GPS IFD → offset 50
        buffer.putInt(0);                                                                           // no IFD1 (offset 46..49)
        buffer.putShort((short) 1).putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0).putInt(0);
        buffer.put("FictionalCam\0".getBytes(StandardCharsets.ISO_8859_1));
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
    /** A PNG with one more chunk right after IHDR. */
    public static byte[] withPngText(byte[] png, String type, String data) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33); out.write(chunk(type, data.getBytes(StandardCharsets.ISO_8859_1))); out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }
    public static byte[] chunk(String type, byte[] data) throws IOException {
        var out = new ByteArrayOutputStream(); var stream = new DataOutputStream(out);
        stream.writeInt(data.length); stream.writeBytes(type); stream.write(data);
        var crc = new CRC32(); crc.update(type.getBytes(StandardCharsets.ISO_8859_1)); crc.update(data); stream.writeInt((int) crc.getValue());
        return out.toByteArray();
    }
    /** The JPEG markers before the first scan. */
    public static java.util.List<Integer> markers(byte[] jpeg) {
        var found = new java.util.ArrayList<Integer>(); int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xff) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xff; found.add(marker);
            if (marker == 0xDA) { break; }
            pos += 2 + ((jpeg[pos + 2] & 0xff) << 8 | jpeg[pos + 3] & 0xff);
        }
        return found;
    }
    public static boolean contains(byte[] bytes, String text) { return indexOf(bytes, text.getBytes(StandardCharsets.ISO_8859_1)) >= 0; }
    public static int indexOf(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= bytes.length; i++) {
            for (int j = 0; j < part.length; j++) { if (bytes[i + j] != part[j]) { continue outer; } }
            return i;
        }
        return -1;
    }
}
