package com.enn3developer.ncapes.server;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.network.CapeImageValidator;
import com.enn3developer.ncapes.network.CapeNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Handles server ownership, saved capes, and the online player roster. */
public final class CapeServerEvents {

    private static final CapeServerEvents INSTANCE = new CapeServerEvents();
    private static final long ACTION_INTERVAL_NANOS = 3_000_000_000L;
    private static final long CAPE_REQUEST_INTERVAL_NANOS = 250_000_000L;
    private static final int MAX_PENDING_CAPE_REQUESTS = 16;
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static final Map<EntityPlayerMP, Long> LAST_CAPE_REQUEST = new IdentityHashMap<>();
    // The newest request replaces an older one that the server has not started yet.
    private static final Map<EntityPlayerMP, Runnable> PENDING_ACTIONS = new ConcurrentHashMap<>();
    private static final Map<EntityPlayerMP, CapeRequestQueue> PENDING_CAPE_REQUESTS = new ConcurrentHashMap<>();
    // Includes null entries for players without a valid saved cape. Only online players are retained.
    private static final Map<EntityPlayerMP, byte[]> ONLINE_CAPES = new IdentityHashMap<>();
    private static boolean registered;

    private CapeServerEvents() {}

    public static synchronized void register() {
        if (!registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(INSTANCE);
            registered = true;
        }
    }

    public static void reset() {
        PENDING_ACTIONS.clear();
        PENDING_CAPE_REQUESTS.clear();
        ONLINE_CAPES.clear();
        LAST_ACTION.clear();
        LAST_CAPE_REQUEST.clear();
    }

    public static void enqueueUpload(EntityPlayerMP sender, long requestId, byte[] pngBytes) {
        PENDING_ACTIONS.put(sender, () -> handleUpload(sender, requestId, pngBytes));
    }

    public static void enqueueClear(EntityPlayerMP sender, long requestId) {
        PENDING_ACTIONS.put(sender, () -> handleClear(sender, requestId));
    }

    public static void enqueueCapeRequest(EntityPlayerMP sender, UUID targetId) {
        if (sender != null && targetId != null) {
            PENDING_CAPE_REQUESTS.computeIfAbsent(sender, ignored -> new CapeRequestQueue())
                .offer(targetId);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        for (Map.Entry<EntityPlayerMP, Runnable> pending : PENDING_ACTIONS.entrySet()) {
            if (PENDING_ACTIONS.remove(pending.getKey(), pending.getValue())) {
                pending.getValue()
                    .run();
            }
        }
        long now = System.nanoTime();
        for (Map.Entry<EntityPlayerMP, CapeRequestQueue> pending : PENDING_CAPE_REQUESTS.entrySet()) {
            EntityPlayerMP sender = pending.getKey();
            if (!isConnected(sender)) {
                PENDING_CAPE_REQUESTS.remove(sender, pending.getValue());
                LAST_CAPE_REQUEST.remove(sender);
                continue;
            }
            Long previous = LAST_CAPE_REQUEST.get(sender);
            if (previous != null && now - previous < CAPE_REQUEST_INTERVAL_NANOS) {
                continue;
            }
            UUID targetId = pending.getValue()
                .poll();
            if (targetId != null) {
                LAST_CAPE_REQUEST.put(sender, now);
                sendCurrentCape(sender, targetId);
            }
        }
    }

    private static void sendCurrentCape(EntityPlayerMP recipient, UUID targetId) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (EntityPlayerMP online : server.getConfigurationManager().playerEntityList) {
            if (targetId.equals(online.getUniqueID())) {
                byte[] bytes = cachedCape(online);
                if (bytes != null) {
                    CapeNetwork.sendCapeTo(recipient, targetId, bytes);
                } else {
                    CapeNetwork.sendCapeRemovedTo(recipient, targetId);
                }
                return;
            }
        }
        CapeNetwork.sendCapeRemovedTo(recipient, targetId);
    }

    public static void handleUpload(EntityPlayerMP sender, long requestId, byte[] pngBytes) {
        if (!isConnected(sender)) {
            return;
        }
        if (!allowAction(sender, requestId)) {
            return;
        }
        if (pngBytes == null) {
            CapeNetwork.sendUploadResult(sender, requestId, false, "Cape upload exceeded the packet limit");
            return;
        }

        try {
            CapeImageValidator.decode(pngBytes);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "Cape PNG could not be decoded" : e.getMessage();
            CapeNetwork.sendUploadResult(sender, requestId, false, message);
            return;
        }

        try {
            CapeStorage.save(sender.getUniqueID(), pngBytes);
            ONLINE_CAPES.put(sender, pngBytes);
            CapeNetwork.broadcastCape(sender.getUniqueID(), pngBytes);
            CapeNetwork.sendUploadResult(sender, requestId, true, "Cape uploaded");
        } catch (IOException e) {
            NCapes.LOG.warn("Could not save cape for {}", sender.getCommandSenderName(), e);
            CapeNetwork.sendUploadResult(sender, requestId, false, "Server could not save the cape");
        }
    }

    public static void handleClear(EntityPlayerMP sender, long requestId) {
        if (!isConnected(sender)) {
            return;
        }
        if (!allowAction(sender, requestId)) {
            return;
        }

        try {
            CapeStorage.remove(sender.getUniqueID());
            ONLINE_CAPES.put(sender, null);
            CapeNetwork.broadcastCapeRemoved(sender.getUniqueID());
            CapeNetwork.sendUploadResult(sender, requestId, true, "Cape removed");
        } catch (IOException e) {
            NCapes.LOG.warn("Could not remove cape for {}", sender.getCommandSenderName(), e);
            CapeNetwork.sendUploadResult(sender, requestId, false, "Server could not remove the cape");
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP joiner = (EntityPlayerMP) event.player;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        boolean joinerSupportsCapes = CapeNetwork.supports(joiner);
        byte[] joiningCape = cachedCape(joiner);
        for (EntityPlayerMP online : server.getConfigurationManager().playerEntityList) {
            if (online == joiner) {
                if (joinerSupportsCapes && joiningCape != null) {
                    CapeNetwork.sendCapeTo(joiner, joiner.getUniqueID(), joiningCape);
                }
                continue;
            }
            if (joinerSupportsCapes) {
                byte[] existingCape = cachedCape(online);
                if (existingCape != null) {
                    CapeNetwork.sendCapeTo(joiner, online.getUniqueID(), existingCape);
                }
            }
            if (joiningCape != null) {
                CapeNetwork.sendCapeTo(online, joiner.getUniqueID(), joiningCape);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP leaving = (EntityPlayerMP) event.player;
        PENDING_ACTIONS.remove(leaving);
        PENDING_CAPE_REQUESTS.remove(leaving);
        ONLINE_CAPES.remove(leaving);
        LAST_ACTION.remove(leaving.getUniqueID());
        LAST_CAPE_REQUEST.remove(leaving);
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (EntityPlayerMP online : server.getConfigurationManager().playerEntityList) {
            if (online != leaving) {
                CapeNetwork.sendCapeRemovedTo(online, leaving.getUniqueID());
            }
        }
    }

    private static byte[] cachedCape(EntityPlayerMP player) {
        if (!ONLINE_CAPES.containsKey(player)) {
            ONLINE_CAPES.put(player, readCape(player.getUniqueID()));
        }
        return ONLINE_CAPES.get(player);
    }

    private static byte[] readCape(UUID playerId) {
        try {
            return CapeStorage.read(playerId);
        } catch (IOException e) {
            NCapes.LOG.warn("Could not read saved cape for {}", playerId, e);
            return null;
        }
    }

    private static boolean isConnected(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager().playerEntityList.contains(player)
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    private static boolean allowAction(EntityPlayerMP sender, long requestId) {
        UUID playerId = sender.getUniqueID();
        long now = System.nanoTime();
        Long previous = LAST_ACTION.get(playerId);
        if (previous != null && now - previous < ACTION_INTERVAL_NANOS) {
            CapeNetwork.sendUploadResult(sender, requestId, false, "Wait 3 seconds before changing your cape again");
            return false;
        }
        LAST_ACTION.put(playerId, now);
        return true;
    }

    private static final class CapeRequestQueue {

        private final ArrayDeque<UUID> requests = new ArrayDeque<>();

        synchronized void offer(UUID targetId) {
            if (requests.size() < MAX_PENDING_CAPE_REQUESTS && !requests.contains(targetId)) {
                requests.addLast(targetId);
            }
        }

        synchronized UUID poll() {
            return requests.pollFirst();
        }
    }
}
