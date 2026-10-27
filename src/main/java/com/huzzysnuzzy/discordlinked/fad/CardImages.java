package com.huzzysnuzzy.discordlinked.fad;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Turns an uploaded profile card picture into a safe, reasonably small file: it must really be a PNG, JPEG or GIF,
 * it's decoded (so nothing but pixels survives), scaled to fit {@value #MAX_WIDTH}×{@value #MAX_HEIGHT} and saved
 * again, as PNG when it has transparency and JPEG otherwise.
 */
public final class CardImages {

    public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_WIDTH = 1600;
    private static final int MAX_HEIGHT = 900;
    /** Pictures this big in pixels are refused before decoding, however small the file. */
    private static final long MAX_PIXELS = 40_000_000L;

    record Image(byte[] bytes, String extension, String hash, int width, int height) {
    }

    private CardImages() {
    }

    static Image process(byte[] upload) throws IOException {
        if (upload.length == 0) {
            throw new IllegalArgumentException("That file is empty.");
        }
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Pictures can be up to 8 MB.");
        }
        BufferedImage source = read(upload);
        double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / source.getWidth(), (double) MAX_HEIGHT / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        byte[] bytes = alpha ? writePng(out) : writeJpeg(out);
        return new Image(bytes, alpha ? "png" : "jpg", hash(bytes), width, height);
    }

    /** Decodes the first frame, checking its size first and reading big ones at a lower resolution. */
    private static BufferedImage read(byte[] upload) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            ImageReader reader = readers != null && readers.hasNext() ? readers.next() : null;
            String format = reader == null ? "" : reader.getFormatName().toLowerCase();
            if (reader == null || !(format.equals("png") || format.equals("jpeg") || format.equals("jpg") || format.equals("gif"))) {
                throw new IllegalArgumentException("That isn't a PNG, JPEG or GIF picture.");
            }
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("That picture is too big (" + width + "×" + height + ").");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                // Skip pixels while reading very large pictures; they're scaled down anyway.
                int step = Math.max(1, Math.max(width / (MAX_WIDTH * 2), height / (MAX_HEIGHT * 2)));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IllegalArgumentException("That picture couldn't be read.");
                }
                return image;
            } catch (IOException | RuntimeException e) {
                if (e instanceof IllegalArgumentException) {
                    throw e;
                }
                throw new IllegalArgumentException("That picture couldn't be read.");
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return out.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return writePng(image);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
