package com.enn3developer.ncapes;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.enn3developer.ncapes.client.ClientCapeManager;
import com.enn3developer.ncapes.client.ClientKeybinds;
import com.enn3developer.ncapes.client.ClientNetworkCompatibility;
import com.enn3developer.ncapes.client.gui.CapeUploadScreen;
import com.enn3developer.ncapes.network.CapeNetwork;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    private ClientCapeManager capeManager;
    private KeyBinding openCapeScreen;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        CapeNetwork.registerClientPackets();
        capeManager = new ClientCapeManager();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        MinecraftForge.EVENT_BUS.register(capeManager);
        FMLCommonHandler.instance()
            .bus()
            .register(capeManager);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        if (!Loader.isModLoaded("controlling")) {
            throw new IllegalStateException("NCapes requires Controlling 2.1.7 or newer on the client");
        }
        openCapeScreen = new KeyBinding("key.ncapes.open", Keyboard.KEY_K, "key.categories.ncapes");
        ClientRegistry.registerKeyBinding(openCapeScreen);
        ClientKeybinds.useControllingDefault(openCapeScreen);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        ClientNetworkCompatibility.allowClientOnlyLibraries();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (openCapeScreen.isPressed() && Minecraft.getMinecraft().thePlayer != null
            && Minecraft.getMinecraft().currentScreen == null) {
            if (!Loader.isModLoaded("modularui2")) {
                Minecraft.getMinecraft().thePlayer
                    .addChatMessage(new ChatComponentText("[NCapes] Install Modular UI 2 to open the cape menu."));
                return;
            }
            CapeUploadScreen.open();
        }
    }

    @Override
    public void onCapeData(NetworkManager source, UUID playerId, byte[] pngBytes) {
        capeManager.queueCape(source, playerId, pngBytes);
    }

    @Override
    public void onCapeRemoved(NetworkManager source, UUID playerId) {
        capeManager.queueRemoval(source, playerId);
    }

    @Override
    public void onCapeUploadResult(long requestId, boolean success, String message) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                if (CapeUploadScreen.setUploadResult(requestId, success, message)
                    && Minecraft.getMinecraft().thePlayer != null) {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("[NCapes] " + message));
                }
            });
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft()
            .func_152344_a(CapeUploadScreen::clearRequestTracking);
    }

    public boolean hasOwnCape() {
        return Minecraft.getMinecraft().thePlayer != null
            && capeManager.hasCape(Minecraft.getMinecraft().thePlayer.getUniqueID());
    }
}
