package com.onebase.brand;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Turns whatever was found or uploaded into the logo we keep: a small PNG.
 *
 * <p><b>Never stored as received.</b> Every logo is decoded to pixels, shrunk to
 * fit {@link #SIZE}×{@link #SIZE}, and written out again as a brand-new PNG.
 * Anything hidden in the original file — extra data, a disguised script, a
 * malformed header — does not survive, and we only ever serve a file we made.
 *
 * <p>Read: PNG, JPEG, GIF, and ICO (the classic favicon — its PNG entries, and
 * its 32-bit bitmap entries). Not read: SVG (it can carry scripts, and needs a
 * full browser engine to draw) and WebP (Java can't decode it). Those give
 * "no logo found", and the Admin can upload one.
 *
 * <p>Pictures larger than {@link #MAX_PIXELS} are refused <b>before</b> decoding:
 * a tiny file can claim to be 50,000 × 50,000 pixels and fill the memory.
 */
final class LogoImages {

	/** The longest side of the stored logo, in pixels. Twice the largest place it is drawn. */
	static final int SIZE = 128;
	/** 4096 × 4096. Refused above, before the pixels are read. */
	static final long MAX_PIXELS = 4096L * 4096L;

	private LogoImages() {
	}

	/** The stored logo, or empty when the bytes are not a picture we can read. */
	static Optional<byte[]> toStoredPng(byte[] bytes) {
		if (bytes == null || bytes.length < 8) return Optional.empty();
		try {
			BufferedImage image = isIco(bytes) ? readIco(bytes) : readSafely(bytes);
			if (image == null || image.getWidth() < 1 || image.getHeight() < 1) return Optional.empty();
			return Optional.of(encodePng(fit(image)));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Size of the picture, read from its header only; refused when too big. */
	private static BufferedImage readSafely(byte[] bytes) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) return null;
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) return null;
			ImageReader reader = readers.next();
			try {
				String format = reader.getFormatName().toLowerCase();
				if (!format.equals("png") && !format.equals("jpeg") && !format.equals("gif")) return null;
				reader.setInput(input, true, true);
				long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
				if (pixels <= 0 || pixels > MAX_PIXELS) return null;
				return reader.read(0);
			} finally {
				reader.dispose();
			}
		}
	}

	private static boolean isIco(byte[] b) {
		return b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0;
	}

	/**
	 * A favicon file holds several sizes. The biggest is taken; it is either a
	 * PNG inside, or a bitmap (read here for 32-bit colour, the usual case).
	 */
	private static BufferedImage readIco(byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int count = buffer.getShort(4) & 0xffff;
		if (count == 0 || count > 64 || bytes.length < 6 + count * 16) return null;
		int bestSize = -1;
		int bestOffset = 0;
		int bestLength = 0;
		for (int i = 0; i < count; i++) {
			int entry = 6 + i * 16;
			int width = bytes[entry] & 0xff;
			int size = width == 0 ? 256 : width;
			int length = buffer.getInt(entry + 8);
			int offset = buffer.getInt(entry + 12);
			if (offset < 0 || length <= 0 || (long) offset + length > bytes.length) continue;
			if (size > bestSize) {
				bestSize = size;
				bestOffset = offset;
				bestLength = length;
			}
		}
		if (bestSize < 0) return null;
		byte[] image = new byte[bestLength];
		System.arraycopy(bytes, bestOffset, image, 0, bestLength);
		if ((image[0] & 0xff) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
			return readSafely(image);
		}
		return readIcoBitmap(image);
	}

	/** A 32-bit bitmap entry of a favicon: rows stored bottom-up, blue-green-red-alpha. */
	private static BufferedImage readIcoBitmap(byte[] dib) {
		ByteBuffer buffer = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
		if (dib.length < 40) return null;
		int headerSize = buffer.getInt(0);
		int width = buffer.getInt(4);
		int height = Math.abs(buffer.getInt(8)) / 2; // the height counts the colour rows and the mask rows
		int bitCount = buffer.getShort(14) & 0xffff;
		if (bitCount != 32 || width <= 0 || height <= 0 || width > 512 || height > 512) return null;
		int pixelsStart = headerSize;
		if (pixelsStart + width * height * 4 > dib.length) return null;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = pixelsStart + ((height - 1 - y) * width + x) * 4;
				int blue = dib[p] & 0xff;
				int green = dib[p + 1] & 0xff;
				int red = dib[p + 2] & 0xff;
				int alpha = dib[p + 3] & 0xff;
				image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
			}
		}
		return image;
	}

	/** Shrunk (never enlarged) to fit SIZE × SIZE, keeping its shape and its transparency. */
	private static BufferedImage fit(BufferedImage source) {
		double scale = Math.min(1.0, (double) SIZE / Math.max(source.getWidth(), source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return target;
	}

	private static byte[] encodePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG writer");
		return out.toByteArray();
	}
}
