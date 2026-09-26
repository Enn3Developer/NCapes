package com.enn3developer.ncapes.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderPlayerEvent;

import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.network.CapeImageValidator;
import com.enn3developer.ncapes.network.CapeNetwork;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientCapeManager {

    private final Map<UUID, ResourceLocation> capes = new HashMap<>();
    private final Map<AbstractClientPlayer, AppliedCape> appliedCapes = new WeakHashMap<>();
    private final Queue<ResourceLocation> availableLocations = new ArrayDeque<>();
    private int nextLocation;

    public boolean hasCape(UUID playerId) {
        return capes.containsKey(playerId);
    }

    public void setCape(UUID playerId, byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length > CapeNetwork.MAX_CAPE_BYTES) {
            NCapes.LOG.warn("Rejected an oversized cape for {}", playerId);
            return;
        }

        try {
            BufferedImage image = CapeImageValidator.decode(pngBytes);
            ResourceLocation location = capes.get(playerId);
            if (location == null) {
                location = availableLocations.poll();
                if (location == null) {
                    location = new ResourceLocation(NCapes.MODID, "capes/slot_" + nextLocation++);
                }
            }
            TextureManager textures = Minecraft.getMinecraft()
                .getTextureManager();
            if (textures.getTexture(location) != null) {
                textures.deleteTexture(location);
            }
            textures.loadTexture(location, new DynamicTexture(image));
            capes.put(playerId, location);
        } catch (IOException | RuntimeException e) {
            NCapes.LOG.warn("Rejected invalid cape data for {}", playerId, e);
        }
    }

    public void removeCape(UUID playerId) {
        ResourceLocation location = capes.remove(playerId);
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

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Specials.Pre event) {
        if (!(event.entityPlayer instanceof AbstractClientPlayer)) {
            return;
        }

        AbstractClientPlayer player = (AbstractClientPlayer) event.entityPlayer;
        ResourceLocation location = capes.get(player.getUniqueID());
        if (location == null) {
            return;
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

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft()
            .func_152344_a(this::clearAll);
    }

    private void clearAll() {
        for (Map.Entry<AbstractClientPlayer, AppliedCape> entry : appliedCapes.entrySet()) {
            restore(entry.getKey(), entry.getValue());
        }
        appliedCapes.clear();
        for (ResourceLocation location : capes.values()) {
            releaseLocation(location);
        }
        capes.clear();
    }

    private void releaseLocation(ResourceLocation location) {
        TextureManager textures = Minecraft.getMinecraft()
            .getTextureManager();
        textures.deleteTexture(location);
        // TextureManager retains its map entry. Replace the pixels and reuse the key.
        textures.loadTexture(location, new DynamicTexture(1, 1));
        availableLocations.add(location);
    }

    private static void restore(AbstractClientPlayer player, AppliedCape applied) {
        if (Objects.equals(player.getLocationCape(), applied.location)) {
            player.func_152121_a(Type.CAPE, applied.original);
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
