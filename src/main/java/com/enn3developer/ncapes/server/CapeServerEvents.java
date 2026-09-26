package com.enn3developer.ncapes.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static final Set<UUID> WARNED_DURING_INTERVAL = new HashSet<>();
    // Keep at most one pending request per connection, even if a client floods upload packets.
    private static final Map<EntityPlayerMP, Runnable> PENDING_ACTIONS = new ConcurrentHashMap<>();
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

    public static void enqueueUpload(EntityPlayerMP sender, byte[] pngBytes) {
        PENDING_ACTIONS.putIfAbsent(sender, () -> handleUpload(sender, pngBytes));
    }

    public static void enqueueClear(EntityPlayerMP sender) {
        PENDING_ACTIONS.putIfAbsent(sender, () -> handleClear(sender));
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
    }

    public static void handleUpload(EntityPlayerMP sender, byte[] pngBytes) {
        if (!isConnected(sender)) {
            return;
        }
        if (!allowAction(sender)) {
            return;
        }
        if (pngBytes == null) {
            CapeNetwork.sendUploadResult(sender, false, "Cape upload exceeded the packet limit");
            return;
        }

        try {
            CapeImageValidator.decode(pngBytes);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "Cape PNG could not be decoded" : e.getMessage();
            CapeNetwork.sendUploadResult(sender, false, message);
            return;
        }

        try {
            CapeStorage.save(sender.getUniqueID(), pngBytes);
            CapeNetwork.broadcastCape(sender.getUniqueID(), pngBytes);
            CapeNetwork.sendUploadResult(sender, true, "Cape uploaded");
        } catch (IOException e) {
            NCapes.LOG.warn("Could not save cape for {}", sender.getCommandSenderName(), e);
            CapeNetwork.sendUploadResult(sender, false, "Server could not save the cape");
        }
    }

    public static void handleClear(EntityPlayerMP sender) {
        if (!isConnected(sender)) {
            return;
        }
        if (!allowAction(sender)) {
            return;
        }

        try {
            CapeStorage.remove(sender.getUniqueID());
            CapeNetwork.broadcastCapeRemoved(sender.getUniqueID());
            CapeNetwork.sendUploadResult(sender, true, "Cape removed");
        } catch (IOException e) {
            NCapes.LOG.warn("Could not remove cape for {}", sender.getCommandSenderName(), e);
            CapeNetwork.sendUploadResult(sender, false, "Server could not remove the cape");
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
        byte[] joiningCape = readCape(joiner.getUniqueID());
        for (EntityPlayerMP online : server.getConfigurationManager().playerEntityList) {
            if (online == joiner) {
                if (joinerSupportsCapes && joiningCape != null) {
                    CapeNetwork.sendCapeTo(joiner, joiner.getUniqueID(), joiningCape);
                }
                continue;
            }
            if (joinerSupportsCapes) {
                byte[] existingCape = readCape(online.getUniqueID());
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
        LAST_ACTION.remove(leaving.getUniqueID());
        WARNED_DURING_INTERVAL.remove(leaving.getUniqueID());
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
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    private static boolean allowAction(EntityPlayerMP sender) {
        UUID playerId = sender.getUniqueID();
        long now = System.nanoTime();
        Long previous = LAST_ACTION.get(playerId);
        if (previous != null && now - previous < ACTION_INTERVAL_NANOS) {
            if (WARNED_DURING_INTERVAL.add(playerId)) {
                CapeNetwork.sendUploadResult(sender, false, "Wait 3 seconds before changing your cape again");
            }
            return false;
        }
        LAST_ACTION.put(playerId, now);
        WARNED_DURING_INTERVAL.remove(playerId);
        return true;
    }
}
