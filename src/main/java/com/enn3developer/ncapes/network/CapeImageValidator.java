package com.enn3developer.ncapes.network;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Checks a cape before it is saved or turned into a client texture. */
public final class CapeImageValidator {

    private static final byte[] PNG_SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    private CapeImageValidator() {}

    public static BufferedImage decode(byte[] pngBytes) throws IOException {
        if (pngBytes == null || pngBytes.length < 45 || pngBytes.length > CapeNetwork.MAX_CAPE_BYTES) {
            throw new IOException("Cape PNG must be at most " + CapeNetwork.MAX_CAPE_BYTES + " bytes");
        }

        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (pngBytes[i] != PNG_SIGNATURE[i]) {
                throw new IOException("Cape file is not a PNG");
            }
        }

        // PNG starts with a 13-byte IHDR. Read dimensions before ImageIO allocates pixels.
        if (readInt(pngBytes, 8) != 13 || pngBytes[12] != 'I'
            || pngBytes[13] != 'H'
            || pngBytes[14] != 'D'
            || pngBytes[15] != 'R') {
            throw new IOException("Cape PNG has no valid header");
        }

        int width = readInt(pngBytes, 16);
        int height = readInt(pngBytes, 20);
        if (!isAllowedSize(width, height)) {
            throw new IOException("Cape must be 64x32, 128x64, 192x96, or 256x128 pixels");
        }

        // Reject forged chunk sizes before passing the bytes to ImageIO.
        checkChunks(pngBytes);

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        } catch (RuntimeException e) {
            throw new IOException("Cape PNG could not be decoded", e);
        }
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("Cape PNG could not be decoded");
        }
        return image;
    }

    private static void checkChunks(byte[] bytes) throws IOException {
        int offset = 8;
        boolean foundImageData = false;
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) {
                throw new IOException("Cape PNG has a truncated chunk");
            }
            int chunkSize = readInt(bytes, offset);
            if (chunkSize < 0 || chunkSize > bytes.length - offset - 12) {
                throw new IOException("Cape PNG has an invalid chunk size");
            }
            boolean imageData = matchesType(bytes, offset + 4, "IDAT");
            boolean end = matchesType(bytes, offset + 4, "IEND");
            foundImageData |= imageData;
            offset += chunkSize + 12;
            if (end) {
                if (chunkSize != 0 || offset != bytes.length || !foundImageData) {
                    throw new IOException("Cape PNG has an invalid end chunk");
                }
                return;
            }
        }
        throw new IOException("Cape PNG has no end chunk");
    }

    private static boolean matchesType(byte[] bytes, int offset, String type) {
        for (int i = 0; i < 4; i++) {
            if (bytes[offset + i] != type.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedSize(int width, int height) {
        return width >= 64 && width <= 256 && width % 64 == 0 && height == width / 2;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }
}
