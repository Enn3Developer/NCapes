package com.enn3developer.ncapes.client;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import com.enn3developer.ncapes.NCapes;

import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.NetworkModHolder;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Lets client-only GUI libraries join servers that do not install them. */
@SideOnly(Side.CLIENT)
public final class ClientNetworkCompatibility {

    private ClientNetworkCompatibility() {}

    public static void allowClientOnlyLibraries() {
        // ModularUI2 and GTNHLib both reject a Forge server that omits them. NCapes uses their
        // client UI only, so keep their normal version check when present and permit absence.
        allowMissingOnServer("modularui2");
        allowMissingOnServer("gtnhlib");
    }

    private static void allowMissingOnServer(String modId) {
        for (Map.Entry<ModContainer, NetworkModHolder> entry : NetworkRegistry.INSTANCE.registry()
            .entrySet()) {
            if (!modId.equals(
                entry.getKey()
                    .getModId())) {
                continue;
            }

            NetworkModHolder holder = entry.getValue();
            if (holder.check(Collections.emptyMap(), Side.SERVER)) {
                return;
            }
            try {
                Field checkerField = NetworkModHolder.class.getDeclaredField("checker");
                checkerField.setAccessible(true);
                NetworkModHolder.NetworkChecker original = (NetworkModHolder.NetworkChecker) checkerField.get(holder);
                NetworkModHolder.NetworkChecker optionalOnServer = holder.new NetworkChecker() {

                    @Override
                    public boolean check(Map<String, String> remoteVersions, Side side) {
                        return side == Side.SERVER && !remoteVersions.containsKey(modId)
                            || original.check(remoteVersions, side);
                    }
                };
                checkerField.set(holder, optionalOnServer);
                holder.testVanillaAcceptance();
                NCapes.LOG.info("Allowing client-only {} to be absent from servers", modId);
            } catch (ReflectiveOperationException | SecurityException e) {
                NCapes.LOG.error("Could not make {} optional on remote servers", modId, e);
            }
            return;
        }
    }
}
