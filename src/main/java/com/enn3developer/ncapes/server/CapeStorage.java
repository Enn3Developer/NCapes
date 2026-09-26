package com.enn3developer.ncapes.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraftforge.common.DimensionManager;

import com.enn3developer.ncapes.network.CapeImageValidator;

/** Stores one cape per player in the active server world's save folder. */
public final class CapeStorage {

    private CapeStorage() {}

    public static byte[] read(UUID playerId) throws IOException {
        Path cape = capePath(playerId);
        if (!Files.isRegularFile(cape)) {
            return null;
        }
        long size = Files.size(cape);
        if (size > com.enn3developer.ncapes.network.CapeNetwork.MAX_CAPE_BYTES) {
            throw new IOException("Stored cape exceeds the size limit");
        }
        byte[] bytes = Files.readAllBytes(cape);
        CapeImageValidator.decode(bytes);
        return bytes;
    }

    public static void save(UUID playerId, byte[] pngBytes) throws IOException {
        CapeImageValidator.decode(pngBytes);
        Path cape = capePath(playerId);
        Files.createDirectories(cape.getParent());
        Path temporary = Files.createTempFile(cape.getParent(), ".ncapes-", ".tmp");
        try {
            Files.write(temporary, pngBytes);
            // An unsupported atomic rename is a failed upload. Keep the old cape intact.
            Files.move(temporary, cape, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("This server filesystem does not support atomic cape saves", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void remove(UUID playerId) throws IOException {
        Files.deleteIfExists(capePath(playerId));
    }

    private static Path capePath(UUID playerId) throws IOException {
        File worldRoot = DimensionManager.getCurrentSaveRootDirectory();
        if (worldRoot == null) {
            throw new IOException("Server world is not available");
        }
        return new File(new File(new File(worldRoot, "ncapes"), "capes"), playerId + ".png").toPath();
    }
}
