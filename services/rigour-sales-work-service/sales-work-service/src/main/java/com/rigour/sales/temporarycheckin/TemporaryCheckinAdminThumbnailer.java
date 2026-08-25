package com.rigour.sales.temporarycheckin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/** 为后台列表按需生成小尺寸 JPEG；原图仍由受保护媒体接口读取。 */
@Component
final class TemporaryCheckinAdminThumbnailer {

    private static final int MAX_EDGE = 320;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;

    Thumbnail create(TemporaryCheckinService.AdminMedia media) {
        if (media.contentType() == null || !media.contentType().startsWith("image/")) {
            throw TemporaryCheckinException.badRequest("仅图片支持缩略图预览");
        }
        try (InputStream original = media.open();
                ImageInputStream input = new MemoryCacheImageInputStream(original)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw TemporaryCheckinException.badRequest("当前图片格式暂不支持缩略图，请下载原图查看");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw TemporaryCheckinException.badRequest("图片尺寸过大，无法生成安全缩略图");
                }
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MAX_EDGE));
                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage source = reader.read(0, readParam);
                return encode(scale(source), thumbnailFilename(media.originalFilename()));
            } finally {
                reader.dispose();
            }
        } catch (TemporaryCheckinException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw TemporaryCheckinException.storage("图片缩略图生成失败");
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        double ratio = Math.min(1d, MAX_EDGE / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static Thumbnail encode(BufferedImage image, String filename) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.82f);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            output.flush();
            return new Thumbnail(bytes.toByteArray(), filename);
        } finally {
            writer.dispose();
        }
    }

    private static String thumbnailFilename(String original) {
        if (original == null || original.isBlank()) return "preview-thumbnail.jpg";
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        base = base.replaceAll("[\\r\\n\\u0000-\\u001f]", "").trim();
        if (base.isEmpty()) base = "preview";
        if (base.length() > 100) base = base.substring(0, 100);
        return base + "-thumbnail.jpg";
    }

    record Thumbnail(byte[] bytes, String filename) { }
}
