package com.enn3developer.ncapes.client;

import net.minecraft.client.settings.KeyBinding;

import com.blamejared.controlling.api.ComboModifier;
import com.blamejared.controlling.api.ControllingApi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Registers the cape menu's default modifier with Controlling. */
@SideOnly(Side.CLIENT)
public final class ClientKeybinds {

    private ClientKeybinds() {}

    public static void useControllingDefault(KeyBinding binding) {
        if (!ControllingApi.setDefaultComboKeyBinding(binding, ComboModifier.CONTROL)) {
            throw new IllegalStateException("Controlling could not register Ctrl+K for the NCapes cape menu");
        }
    }
}
