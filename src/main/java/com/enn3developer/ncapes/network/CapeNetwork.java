package com.enn3developer.ncapes.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.server.CapeServerEvents;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;

/** Packet protocol for client uploads and server-owned cape distribution. */
public final class CapeNetwork {

    // C17PacketCustomPayload rejects payloads at 32767 bytes, including our discriminator and framing.
    public static final int MAX_CAPE_BYTES = 24 * 1024;

    private static final AttributeKey<Boolean> REMOTE_CHANNEL = new AttributeKey<>("ncapes:remoteChannel");
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static SimpleNetworkWrapper channel;

    private CapeNetwork() {}

    public static synchronized void init() {
        if (channel != null) {
            return;
        }
        SimpleNetworkWrapper created = NetworkRegistry.INSTANCE.newSimpleChannel(NCapes.MODID);
        // Register both directions on both physical sides. The server needs the S2C encoder's discriminator.
        created.registerMessage(UploadHandler.class, UploadPacket.class, 0, Side.SERVER);
        created.registerMessage(ClearHandler.class, ClearPacket.class, 1, Side.SERVER);
        created.registerMessage(CapeDataHandler.class, CapeDataPacket.class, 2, Side.CLIENT);
        created.registerMessage(CapeRemovedHandler.class, CapeRemovedPacket.class, 3, Side.CLIENT);
        created.registerMessage(UploadResultHandler.class, UploadResultPacket.class, 4, Side.CLIENT);
        created.registerMessage(RequestCapeHandler.class, RequestCapePacket.class, 5, Side.SERVER);
        FMLCommonHandler.instance()
            .bus()
            .register(new PeerRegistrationHandler());
        channel = created;
    }

    /** Safe for ClientProxy to call; all packet classes are side-neutral. */
    public static void registerClientPackets() {
        init();
    }

    public static long nextRequestId() {
        long requestId = REQUEST_IDS.incrementAndGet();
        if (requestId <= 0) {
            throw new IllegalStateException("Cape request IDs are exhausted");
        }
        return requestId;
    }

    public static void upload(long requestId, byte[] pngBytes) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("Cape request ID must be positive");
        }
        if (pngBytes == null || pngBytes.length == 0 || pngBytes.length > MAX_CAPE_BYTES) {
            throw new IllegalArgumentException("Cape PNG must be at most " + MAX_CAPE_BYTES + " bytes");
        }
        if (!isServerAvailable()) {
            throw new IllegalStateException("This server does not support NCapes");
        }
        init();
        channel.sendToServer(new UploadPacket(requestId, Arrays.copyOf(pngBytes, pngBytes.length)));
    }

    public static void clear(long requestId) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("Cape request ID must be positive");
        }
        if (!isServerAvailable()) {
            throw new IllegalStateException("This server does not support NCapes");
        }
        init();
        channel.sendToServer(new ClearPacket(requestId));
    }

    public static void requestCape(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Cape player ID is required");
        }
        if (!isServerAvailable()) {
            throw new IllegalStateException("This server does not support NCapes");
        }
        init();
        channel.sendToServer(new RequestCapePacket(playerId));
    }

    /** Whether the current connection's server registered the NCapes packet channel. */
    public static boolean isServerAvailable() {
        NetworkManager manager = FMLCommonHandler.instance()
            .getClientToServerNetworkManager();
        return hasRemoteChannel(manager);
    }

    /** Whether this player can receive NCapes packets. */
    public static boolean supports(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && hasRemoteChannel(player.playerNetServerHandler.netManager);
    }

    private static boolean hasRemoteChannel(NetworkManager manager) {
        return manager != null && manager.isChannelOpen()
            && Boolean.TRUE.equals(
                manager.channel()
                    .attr(REMOTE_CHANNEL)
                    .get());
    }

    public static void sendCapeTo(EntityPlayerMP recipient, UUID playerId, byte[] pngBytes) {
        if (supports(recipient)) {
            channel.sendTo(new CapeDataPacket(playerId, pngBytes), recipient);
        }
    }

    public static void broadcastCape(UUID playerId, byte[] pngBytes) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
                sendCapeTo(player, playerId, pngBytes);
            }
        }
    }

    public static void broadcastCapeRemoved(UUID playerId) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
                sendCapeRemovedTo(player, playerId);
            }
        }
    }

    public static void sendCapeRemovedTo(EntityPlayerMP recipient, UUID playerId) {
        if (supports(recipient)) {
            channel.sendTo(new CapeRemovedPacket(playerId), recipient);
        }
    }

    public static void sendUploadResult(EntityPlayerMP recipient, long requestId, boolean success, String message) {
        if (supports(recipient)) {
            channel.sendTo(new UploadResultPacket(requestId, success, message), recipient);
        }
    }

    public static final class PeerRegistrationHandler {

        @SubscribeEvent
        public void onChannelRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
            if (!event.registrations.contains(NCapes.MODID) || event.manager == null) {
                return;
            }
            if ("REGISTER".equals(event.operation)) {
                event.manager.channel()
                    .attr(REMOTE_CHANNEL)
                    .set(true);
            } else if ("UNREGISTER".equals(event.operation)) {
                event.manager.channel()
                    .attr(REMOTE_CHANNEL)
                    .set(false);
            }
        }
    }

    private static void writePlayerId(ByteBuf buffer, UUID playerId) {
        buffer.writeLong(playerId.getMostSignificantBits());
        buffer.writeLong(playerId.getLeastSignificantBits());
    }

    private static UUID readPlayerId(ByteBuf buffer) {
        if (buffer.readableBytes() < 16) {
            return null;
        }
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    private static void writeCapeBytes(ByteBuf buffer, byte[] pngBytes) {
        buffer.writeShort(pngBytes.length);
        buffer.writeBytes(pngBytes);
    }

    private static byte[] readCapeBytes(ByteBuf buffer) {
        if (buffer.readableBytes() < 2) {
            return null;
        }
        int length = buffer.readUnsignedShort();
        if (length == 0 || length > MAX_CAPE_BYTES || length != buffer.readableBytes()) {
            return null;
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }

    public static final class UploadPacket implements IMessage {

        private long requestId;
        private byte[] pngBytes;

        public UploadPacket() {}

        private UploadPacket(long requestId, byte[] pngBytes) {
            this.requestId = requestId;
            this.pngBytes = pngBytes;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() < 8) {
                return;
            }
            requestId = buffer.readLong();
            pngBytes = readCapeBytes(buffer);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(requestId);
            writeCapeBytes(buffer, pngBytes);
        }
    }

    public static final class ClearPacket implements IMessage {

        private long requestId;

        public ClearPacket() {}

        private ClearPacket(long requestId) {
            this.requestId = requestId;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() == 8) {
                requestId = buffer.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(requestId);
        }
    }

    public static final class RequestCapePacket implements IMessage {

        private UUID playerId;

        public RequestCapePacket() {}

        private RequestCapePacket(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() == 16) {
                playerId = readPlayerId(buffer);
            }
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            writePlayerId(buffer, playerId);
        }
    }

    public static final class CapeDataPacket implements IMessage {

        private UUID playerId;
        private byte[] pngBytes;

        public CapeDataPacket() {}

        private CapeDataPacket(UUID playerId, byte[] pngBytes) {
            this.playerId = playerId;
            this.pngBytes = pngBytes;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            playerId = readPlayerId(buffer);
            pngBytes = playerId == null ? null : readCapeBytes(buffer);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            writePlayerId(buffer, playerId);
            writeCapeBytes(buffer, pngBytes);
        }
    }

    public static final class CapeRemovedPacket implements IMessage {

        private UUID playerId;

        public CapeRemovedPacket() {}

        private CapeRemovedPacket(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            playerId = readPlayerId(buffer);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            writePlayerId(buffer, playerId);
        }
    }

    public static final class UploadResultPacket implements IMessage {

        private long requestId;
        private boolean success;
        private String message;

        public UploadResultPacket() {}

        private UploadResultPacket(long requestId, boolean success, String message) {
            this.requestId = requestId;
            this.success = success;
            this.message = message;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() < 10) {
                return;
            }
            requestId = buffer.readLong();
            success = buffer.readBoolean();
            int length = buffer.readUnsignedByte();
            if (length > 0 && length == buffer.readableBytes()) {
                byte[] bytes = new byte[length];
                buffer.readBytes(bytes);
                message = new String(bytes, StandardCharsets.UTF_8);
            }
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 255) {
                throw new IllegalArgumentException("Upload result is too long");
            }
            buffer.writeLong(requestId);
            buffer.writeBoolean(success);
            buffer.writeByte(bytes.length);
            buffer.writeBytes(bytes);
        }
    }

    public static final class UploadHandler implements IMessageHandler<UploadPacket, IMessage> {

        @Override
        public IMessage onMessage(UploadPacket packet, MessageContext context) {
            EntityPlayerMP sender = context.getServerHandler().playerEntity;
            if (sender != null && packet.requestId > 0) {
                CapeServerEvents.enqueueUpload(sender, packet.requestId, packet.pngBytes);
            }
            return null;
        }
    }

    public static final class ClearHandler implements IMessageHandler<ClearPacket, IMessage> {

        @Override
        public IMessage onMessage(ClearPacket packet, MessageContext context) {
            EntityPlayerMP sender = context.getServerHandler().playerEntity;
            if (sender != null && packet.requestId > 0) {
                CapeServerEvents.enqueueClear(sender, packet.requestId);
            }
            return null;
        }
    }

    public static final class RequestCapeHandler implements IMessageHandler<RequestCapePacket, IMessage> {

        @Override
        public IMessage onMessage(RequestCapePacket packet, MessageContext context) {
            EntityPlayerMP sender = context.getServerHandler().playerEntity;
            if (sender != null && packet.playerId != null) {
                CapeServerEvents.enqueueCapeRequest(sender, packet.playerId);
            }
            return null;
        }
    }

    public static final class CapeDataHandler implements IMessageHandler<CapeDataPacket, IMessage> {

        @Override
        public IMessage onMessage(CapeDataPacket packet, MessageContext context) {
            if (packet.playerId != null && packet.pngBytes != null) {
                NCapes.proxy.onCapeData(
                    context.getClientHandler()
                        .getNetworkManager(),
                    packet.playerId,
                    packet.pngBytes);
            }
            return null;
        }
    }

    public static final class CapeRemovedHandler implements IMessageHandler<CapeRemovedPacket, IMessage> {

        @Override
        public IMessage onMessage(CapeRemovedPacket packet, MessageContext context) {
            if (packet.playerId != null) {
                NCapes.proxy.onCapeRemoved(
                    context.getClientHandler()
                        .getNetworkManager(),
                    packet.playerId);
            }
            return null;
        }
    }

    public static final class UploadResultHandler implements IMessageHandler<UploadResultPacket, IMessage> {

        @Override
        public IMessage onMessage(UploadResultPacket packet, MessageContext context) {
            if (packet.requestId > 0 && packet.message != null) {
                NCapes.proxy.onCapeUploadResult(packet.requestId, packet.success, packet.message);
            }
            return null;
        }
    }
}
