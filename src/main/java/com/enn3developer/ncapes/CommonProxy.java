package com.enn3developer.ncapes;

import java.util.UUID;

import com.enn3developer.ncapes.network.CapeNetwork;
import com.enn3developer.ncapes.server.CapeServerEvents;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        CapeNetwork.init();
        CapeServerEvents.register();
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void onCapeData(UUID playerId, byte[] pngBytes) {}

    public void onCapeRemoved(UUID playerId) {}

    public void onCapeUploadResult(long requestId, boolean success, String message) {}
}
