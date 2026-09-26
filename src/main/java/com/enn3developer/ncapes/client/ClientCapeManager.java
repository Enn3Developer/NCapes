package com.enn3developer.ncapes.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderPlayerEvent;

import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.network.CapeImageValidator;
import com.enn3developer.ncapes.network.CapeNetwork;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientCapeManager {

    private static final int MAX_PENDING_UPDATES = 256;
    private static final int UPDATES_PER_TICK = 16;
    private static final int MAX_CACHED_CAPES = 512;
    private static final int MAX_LOADED_TEXTURES = 256;
    private static final int MAX_KNOWN_ABSENT = 512;
    private static final int MAX_REQUEST_HISTORY = 512;
    private static final int TEXTURE_LOADS_PER_TICK = 4;
    private static final long REQUEST_INTERVAL_NANOS = 250_000_000L;
    private static final long REQUEST_RETRY_NANOS = 10_000_000_000L;

    // Network handlers may run off the client thread. Each UUID keeps only its latest update.
    private final Object pendingLock = new Object();
    private final LinkedHashMap<UUID, CapeUpdate> pendingUpdates = new LinkedHashMap<>();
    private NetworkManager activeConnection;
    private long connectionEpoch;
    private boolean clearOnNextTick;

    // Only rendered players need a decoded image and a GL texture.
    private final LinkedHashMap<UUID, byte[]> capeBytes = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<UUID, ResourceLocation> texturesByPlayer = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<UUID, Boolean> knownAbsent = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<UUID, Long> requestedAt = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<AbstractClientPlayer, AppliedCape> appliedCapes = new WeakHashMap<>();
    private final Queue<ResourceLocation> availableLocations = new ArrayDeque<>();
    private int nextLocation;
    private int remainingTextureLoads = TEXTURE_LOADS_PER_TICK;
    private long nextRequestAt;

    public boolean hasCape(UUID playerId) {
        return capeBytes.containsKey(playerId);
    }

    public void queueCape(NetworkManager source, UUID playerId, byte[] pngBytes) {
        if (source == null || playerId == null
            || pngBytes == null
            || pngBytes.length == 0
            || pngBytes.length > CapeNetwork.MAX_CAPE_BYTES) {
            return;
        }
        queueUpdate(source, playerId, new CapeUpdate(pngBytes));
    }

    public void queueRemoval(NetworkManager source, UUID playerId) {
        if (source != null && playerId != null) {
            queueUpdate(source, playerId, CapeUpdate.REMOVED);
        }
    }

    private void queueUpdate(NetworkManager source, UUID playerId, CapeUpdate update) {
        synchronized (pendingLock) {
            if (source != activeConnection) {
                return;
            }
            pendingUpdates.remove(playerId);
            if (pendingUpdates.size() == MAX_PENDING_UPDATES) {
                pendingUpdates.remove(
                    pendingUpdates.keySet()
                        .iterator()
                        .next());
            }
            pendingUpdates.put(playerId, update);
        }
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        synchronized (pendingLock) {
            activeConnection = event.manager;
            connectionEpoch++;
            pendingUpdates.clear();
            clearOnNextTick = true;
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        synchronized (pendingLock) {
            if (event.manager != activeConnection) {
                return;
            }
            activeConnection = null;
            connectionEpoch++;
            pendingUpdates.clear();
            clearOnNextTick = true;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        List<Map.Entry<UUID, CapeUpdate>> updates = new ArrayList<>(UPDATES_PER_TICK);
        boolean clear;
        long epoch;
        synchronized (pendingLock) {
            clear = clearOnNextTick;
            clearOnNextTick = false;
            epoch = connectionEpoch;
            Iterator<Map.Entry<UUID, CapeUpdate>> iterator = pendingUpdates.entrySet()
                .iterator();
            while (iterator.hasNext() && updates.size() < UPDATES_PER_TICK) {
                Map.Entry<UUID, CapeUpdate> entry = iterator.next();
                updates.add(new AbstractMap.SimpleImmutableEntry<>(entry));
                iterator.remove();
            }
        }

        if (clear) {
            clearAll();
        }
        remainingTextureLoads = TEXTURE_LOADS_PER_TICK;
        for (Map.Entry<UUID, CapeUpdate> update : updates) {
            synchronized (pendingLock) {
                if (epoch != connectionEpoch || activeConnection == null) {
                    return;
                }
            }
            if (update.getValue().bytes == null) {
                removeCape(update.getKey());
            } else {
                storeCape(update.getKey(), update.getValue().bytes);
            }
        }

        if (Minecraft.getMinecraft().thePlayer != null) {
            UUID ownId = Minecraft.getMinecraft().thePlayer.getUniqueID();
            if (!capeBytes.containsKey(ownId) && !knownAbsent.containsKey(ownId)) {
                requestMissing(ownId);
            }
        }
    }

    private void storeCape(UUID playerId, byte[] bytes) {
        removeTexture(playerId);
        capeBytes.put(playerId, bytes);
        knownAbsent.remove(playerId);
        requestedAt.remove(playerId);
        while (capeBytes.size() > MAX_CACHED_CAPES) {
            UUID victim = oldestUnpinnedCape();
            if (victim == null) {
                break;
            }
            capeBytes.remove(victim);
            removeTexture(victim);
        }
    }

    private UUID oldestUnpinnedCape() {
        UUID ownId = Minecraft.getMinecraft().thePlayer == null ? null
            : Minecraft.getMinecraft().thePlayer.getUniqueID();
        for (UUID playerId : capeBytes.keySet()) {
            if (!playerId.equals(ownId)) {
                return playerId;
            }
        }
        return null;
    }

    private void removeCape(UUID playerId) {
        capeBytes.remove(playerId);
        removeTexture(playerId);
        requestedAt.remove(playerId);
        knownAbsent.put(playerId, true);
        trimOldest(knownAbsent, MAX_KNOWN_ABSENT);
    }

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Specials.Pre event) {
        if (!(event.entityPlayer instanceof AbstractClientPlayer)) {
            return;
        }

        AbstractClientPlayer player = (AbstractClientPlayer) event.entityPlayer;
        UUID playerId = player.getUniqueID();
        byte[] bytes = capeBytes.get(playerId);
        if (bytes == null) {
            if (!knownAbsent.containsKey(playerId)) {
                requestMissing(playerId);
            }
            return;
        }

        ResourceLocation location = texturesByPlayer.get(playerId);
        if (location == null) {
            location = loadTexture(playerId, bytes);
            if (location == null) {
                return;
            }
        }

        ResourceLocation current = player.getLocationCape();
        AppliedCape applied = appliedCapes.get(player);
        if (applied == null) {
            applied = new AppliedCape(current);
            appliedCapes.put(player, applied);
        } else if (!Objects.equals(current, applied.location)) {
            applied.original = current;
        }
        applied.location = location;
        player.func_152121_a(Type.CAPE, location);
    }

    private ResourceLocation loadTexture(UUID playerId, byte[] bytes) {
        if (remainingTextureLoads == 0) {
            return null;
        }
        remainingTextureLoads--;

        try {
            BufferedImage image = CapeImageValidator.decode(bytes);
            if (texturesByPlayer.size() == MAX_LOADED_TEXTURES) {
                removeTexture(
                    texturesByPlayer.keySet()
                        .iterator()
                        .next());
            }
            ResourceLocation location = availableLocations.poll();
            if (location == null) {
                location = new ResourceLocation(NCapes.MODID, "capes/slot_" + nextLocation++);
            }
            TextureManager textures = Minecraft.getMinecraft()
                .getTextureManager();
            if (textures.getTexture(location) != null) {
                textures.deleteTexture(location);
            }
            textures.loadTexture(location, new DynamicTexture(image));
            texturesByPlayer.put(playerId, location);
            return location;
        } catch (IOException | RuntimeException e) {
            capeBytes.remove(playerId);
            knownAbsent.put(playerId, true);
            trimOldest(knownAbsent, MAX_KNOWN_ABSENT);
            NCapes.LOG.warn("Rejected invalid cape data for {}", playerId, e);
            return null;
        }
    }

    private void requestMissing(UUID playerId) {
        synchronized (pendingLock) {
            if (activeConnection == null) {
                return;
            }
        }
        if (!CapeNetwork.isServerAvailable()) {
            return;
        }

        long now = System.nanoTime();
        Long previous = requestedAt.get(playerId);
        if (now < nextRequestAt || previous != null && now - previous < REQUEST_RETRY_NANOS) {
            return;
        }
        try {
            CapeNetwork.requestCape(playerId);
            nextRequestAt = now + REQUEST_INTERVAL_NANOS;
            requestedAt.put(playerId, now);
            trimOldest(requestedAt, MAX_REQUEST_HISTORY);
        } catch (IllegalStateException ignored) {
            // The connection closed between the availability check and the send.
        }
    }

    private void removeTexture(UUID playerId) {
        ResourceLocation location = texturesByPlayer.remove(playerId);
        if (location == null) {
            return;
        }
        appliedCapes.entrySet()
            .removeIf(entry -> {
                if (playerId.equals(
                    entry.getKey()
                        .getUniqueID())) {
                    restore(entry.getKey(), entry.getValue());
                    return true;
                }
                return false;
            });
        releaseLocation(location);
    }

    private void clearAll() {
        for (Map.Entry<AbstractClientPlayer, AppliedCape> entry : appliedCapes.entrySet()) {
            restore(entry.getKey(), entry.getValue());
        }
        appliedCapes.clear();
        for (ResourceLocation location : texturesByPlayer.values()) {
            releaseLocation(location);
        }
        texturesByPlayer.clear();
        capeBytes.clear();
        knownAbsent.clear();
        requestedAt.clear();
        nextRequestAt = 0;
    }

    private void releaseLocation(ResourceLocation location) {
        TextureManager textures = Minecraft.getMinecraft()
            .getTextureManager();
        textures.deleteTexture(location);
        // TextureManager keeps its map key. Replace its pixels before reusing the slot.
        textures.loadTexture(location, new DynamicTexture(1, 1));
        availableLocations.add(location);
    }

    private static <V> void trimOldest(LinkedHashMap<UUID, V> entries, int limit) {
        if (entries.size() > limit) {
            entries.remove(
                entries.keySet()
                    .iterator()
                    .next());
        }
    }

    private static void restore(AbstractClientPlayer player, AppliedCape applied) {
        if (Objects.equals(player.getLocationCape(), applied.location)) {
            player.func_152121_a(Type.CAPE, applied.original);
        }
    }

    private static class CapeUpdate {

        private static final CapeUpdate REMOVED = new CapeUpdate(null);
        private final byte[] bytes;

        private CapeUpdate(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private static class AppliedCape {

        private ResourceLocation original;
        private ResourceLocation location;

        private AppliedCape(ResourceLocation original) {
            this.original = original;
        }
    }
}
