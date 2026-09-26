package com.enn3developer.ncapes;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.enn3developer.ncapes.client.ClientCapeManager;
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
        openCapeScreen = new KeyBinding("key.ncapes.open", Keyboard.KEY_K, "key.categories.ncapes");
        ClientRegistry.registerKeyBinding(openCapeScreen);
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
    public void onCapeData(UUID playerId, byte[] pngBytes) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> capeManager.setCape(playerId, pngBytes));
    }

    @Override
    public void onCapeRemoved(UUID playerId) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> capeManager.removeCape(playerId));
    }

    @Override
    public void onCapeUploadResult(boolean success, String message) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                CapeUploadScreen.setUploadResult(success, message);
                if (Minecraft.getMinecraft().thePlayer != null) {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("[NCapes] " + message));
                }
            });
    }

    public boolean hasOwnCape() {
        return Minecraft.getMinecraft().thePlayer != null
            && capeManager.hasCape(Minecraft.getMinecraft().thePlayer.getUniqueID());
    }
}
