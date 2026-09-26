package com.enn3developer.ncapes.client.gui;

import java.lang.reflect.Method;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Isolated so ordinary LWJGL2 clients never load SDL classes. */
@SideOnly(Side.CLIENT)
public final class SdlCapeFileDrops {

    private static volatile int windowId;
    private static final SDL_EventFilter WATCHER = SDL_EventFilter.create((userdata, eventAddress) -> {
        SDL_Event event = SDL_Event.create(eventAddress);
        if (event.type() == SDLEvents.SDL_EVENT_DROP_FILE && event.drop()
            .windowID() == windowId) {
            // SDL owns the native string. Copy it before the callback returns.
            CapeFileDrops.offer(
                event.drop()
                    .dataString());
        }
        return true;
    });

    private static boolean started;

    private SdlCapeFileDrops() {}

    public static synchronized boolean start() throws ReflectiveOperationException {
        if (started) return true;
        Class<?> display = Class.forName("org.lwjglx.opengl.Display");
        Method getWindow = display.getMethod("getWindow");
        long window = (Long) getWindow.invoke(null);
        if (window == 0) return false;
        windowId = SDLVideo.SDL_GetWindowID(window);
        if (windowId == 0) return false;
        started = SDLEvents.SDL_AddEventWatch(WATCHER, 0L);
        return started;
    }

    public static synchronized void stop() {
        if (!started) return;
        SDLEvents.SDL_RemoveEventWatch(WATCHER, 0L);
        started = false;
        windowId = 0;
    }
}
