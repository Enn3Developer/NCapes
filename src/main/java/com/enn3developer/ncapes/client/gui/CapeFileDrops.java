package com.enn3developer.ncapes.client.gui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;

import com.enn3developer.ncapes.NCapes;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** File drops from the game window, when lwjgl3ify's SDL3 window is available. */
@SideOnly(Side.CLIENT)
public final class CapeFileDrops {

    private static final String ADAPTER = "com.enn3developer.ncapes.client.gui.SdlCapeFileDrops";
    private static final ArrayBlockingQueue<Path> pending = new ArrayBlockingQueue<>(8);
    private static Method stop;
    private static volatile boolean enabled;

    private CapeFileDrops() {}

    /** Starts listening for native drops. Returns false on clients using stock LWJGL2. */
    public static synchronized boolean enable() {
        if (enabled) return true;
        pending.clear();
        try {
            Class.forName("org.lwjgl.sdl.SDLEvents", false, CapeFileDrops.class.getClassLoader());
            Class.forName("org.lwjglx.opengl.Display", false, CapeFileDrops.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        try {
            Class<?> adapter = Class.forName(ADAPTER);
            Method removeWatch = adapter.getMethod("stop");
            boolean started = (Boolean) adapter.getMethod("start")
                .invoke(null);
            if (started) {
                stop = removeWatch;
                enabled = true;
            }
            return started;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            NCapes.LOG.warn("Could not enable cape file drops", cause(e));
            return false;
        }
    }

    /** Poll on Minecraft's client thread. A drop returns a local path, or null if none arrived. */
    public static Path poll() {
        return pending.poll();
    }

    public static synchronized void disable() {
        boolean wasEnabled = enabled;
        enabled = false;
        if (wasEnabled && stop != null) {
            try {
                stop.invoke(null);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                NCapes.LOG.warn("Could not remove cape file drop listener", cause(e));
            }
        }
        stop = null;
        pending.clear();
    }

    static void offer(String filename) {
        if (filename == null || !enabled) return;
        try {
            pending.offer(Paths.get(filename));
        } catch (InvalidPathException e) {
            NCapes.LOG.warn("Ignoring invalid dropped file path", e);
        }
    }

    private static Throwable cause(Throwable exception) {
        if (exception instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) exception).getCause();
            if (cause != null) return cause;
        }
        return exception;
    }
}
