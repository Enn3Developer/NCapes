package com.enn3developer.ncapes.client.gui;

import java.awt.AWTError;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.enn3developer.ncapes.ClientProxy;
import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.network.CapeImageValidator;
import com.enn3developer.ncapes.network.CapeNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CapeUploadScreen {

    private static final long ACTION_INTERVAL_NANOS = 3_100_000_000L;
    private static final ExecutorService FILE_READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NCapes file reader");
        thread.setDaemon(true);
        return thread;
    });

    private static CapeUploadScreen active;
    private static long lastRequestNanos;
    private static String lastDirectory;

    private boolean busy;
    private boolean dropEnabled;
    private String status = "Choose a PNG to upload your cape.";
    private volatile FileDialog fileDialog;

    private CapeUploadScreen() {}

    public static void open() {
        CapeUploadScreen uploadScreen = new CapeUploadScreen();
        active = uploadScreen;
        ClientGUI.open(uploadScreen.createScreen());
    }

    public static void setUploadResult(boolean success, String message) {
        CapeUploadScreen uploadScreen = active;
        if (uploadScreen != null) {
            uploadScreen.busy = false;
            uploadScreen.status = (success ? "Done: " : "Failed: ") + message;
        }
    }

    private ModularScreen createScreen() {
        ButtonWidget<?> choose = actionButton("Choose PNG...", 125, this::chooseFile)
            .setEnabledIf(widget -> CapeNetwork.isServerAvailable() && !busy);
        ButtonWidget<?> remove = actionButton("Remove cape", 100, this::removeCape)
            .setEnabledIf(widget -> CapeNetwork.isServerAvailable() && !busy && hasOwnCape());

        ModularPanel panel = ModularPanel.defaultPanel("cape_upload", 320, 130)
            .padding(10)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .child(
                        new TextWidget<>("Your cape").widthRel(1f)
                            .height(17))
                    .child(
                        new TextWidget<>(IKey.dynamic(this::instruction)).widthRel(1f)
                            .height(17))
                    .child(
                        new TextWidget<>("PNG: 64x32, 128x64, 192x96 or 256x128").widthRel(1f)
                            .height(15))
                    .child(
                        new TextWidget<>("Maximum file size: 24 KiB").widthRel(1f)
                            .height(18))
                    .child(
                        Flow.row()
                            .widthRel(1f)
                            .height(20)
                            .child(choose)
                            .child(remove))
                    .child(
                        new TextWidget<>(IKey.dynamic(this::displayStatus)).widthRel(1f)
                            .height(20)));

        return new ModularScreen(NCapes.MODID, panel) {

            @Override
            public void onOpen() {
                super.onOpen();
                dropEnabled = CapeNetwork.isServerAvailable() && CapeFileDrops.enable();
            }

            @Override
            public void onUpdate() {
                super.onUpdate();
                Path dropped;
                while ((dropped = CapeFileDrops.poll()) != null) {
                    if (active == CapeUploadScreen.this && !busy) {
                        uploadFile(dropped);
                    }
                }
            }

            @Override
            public void onClose() {
                CapeFileDrops.disable();
                FileDialog dialog = fileDialog;
                if (dialog != null) {
                    EventQueue.invokeLater(dialog::dispose);
                }
                if (active == CapeUploadScreen.this) {
                    active = null;
                }
                super.onClose();
            }
        };
    }

    private String instruction() {
        return dropEnabled ? "Drop a PNG here, or choose one from your computer."
            : "Choose a PNG from your computer. It uploads right away.";
    }

    private String displayStatus() {
        return CapeNetwork.isServerAvailable() ? status : "This server does not support NCapes.";
    }

    private static boolean hasOwnCape() {
        return ((ClientProxy) NCapes.proxy).hasOwnCape();
    }

    private static ButtonWidget<?> actionButton(String label, int width, Runnable action) {
        return new ButtonWidget<>().size(width, 20)
            .overlay(IKey.str(label))
            .onMousePressed(button -> {
                if (button != 0) return false;
                action.run();
                return true;
            });
    }

    private void chooseFile() {
        if (busy || !canSend()) return;
        busy = true;
        status = "Choose a PNG file...";
        try {
            EventQueue.invokeLater(() -> {
                Path selected = null;
                String error = null;
                FileDialog dialog = null;
                try {
                    dialog = new FileDialog((Frame) null, "Choose cape PNG", FileDialog.LOAD);
                    fileDialog = dialog;
                    dialog.setAlwaysOnTop(true);
                    dialog.setFilenameFilter(
                        (directory, name) -> name.toLowerCase(Locale.ROOT)
                            .endsWith(".png"));
                    if (lastDirectory != null) {
                        dialog.setDirectory(lastDirectory);
                    } else {
                        dialog.setDirectory(System.getProperty("user.home"));
                    }
                    dialog.setVisible(true);
                    File[] chosen = dialog.getFiles();
                    if (chosen.length > 0) {
                        selected = chosen[0].toPath();
                        lastDirectory = chosen[0].getParent();
                    }
                } catch (RuntimeException | AWTError e) {
                    error = "Could not open the file picker.";
                    NCapes.LOG.warn("Could not open cape file picker", e);
                } finally {
                    fileDialog = null;
                    if (dialog != null) dialog.dispose();
                }
                Path chosenFile = selected;
                String failure = error;
                Minecraft.getMinecraft()
                    .func_152344_a(() -> {
                        if (active != CapeUploadScreen.this) return;
                        busy = false;
                        if (failure != null) {
                            status = failure;
                        } else if (chosenFile != null) {
                            uploadFile(chosenFile);
                        } else {
                            status = "No file selected.";
                        }
                    });
            });
        } catch (RuntimeException | AWTError e) {
            busy = false;
            status = "Could not open the file picker.";
            NCapes.LOG.warn("Could not start cape file picker", e);
        }
    }

    private void uploadFile(Path file) {
        if (busy || !canSend()) return;
        busy = true;
        status = "Checking cape PNG...";
        FILE_READER.execute(() -> {
            byte[] bytes = null;
            String error = null;
            try {
                if (file.getFileName() == null || !file.getFileName()
                    .toString()
                    .toLowerCase(Locale.ROOT)
                    .endsWith(".png") || !Files.isRegularFile(file)) {
                    throw new IOException("Choose a PNG file.");
                }
                long size = Files.size(file);
                if (size == 0 || size > CapeNetwork.MAX_CAPE_BYTES) {
                    throw new IOException("Cape PNG must be at most 24 KiB.");
                }
                bytes = readBounded(file);
                CapeImageValidator.decode(bytes);
            } catch (IOException | RuntimeException e) {
                error = readableError(e);
                NCapes.LOG.warn("Could not use cape file {}", file, e);
            }
            byte[] checkedBytes = bytes;
            String failure = error;
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    if (active != CapeUploadScreen.this) return;
                    busy = false;
                    if (failure != null) {
                        status = failure;
                    } else if (canSend()) {
                        try {
                            CapeNetwork.upload(checkedBytes);
                            lastRequestNanos = System.nanoTime();
                            busy = true;
                            status = "Uploading cape...";
                        } catch (RuntimeException e) {
                            status = "Could not send the cape to this server.";
                            NCapes.LOG.warn("Could not send cape upload", e);
                        }
                    }
                });
        });
    }

    private static String readableError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("24 KiB")) return "That PNG exceeds the 24 KiB limit.";
        if (message != null && message.contains("Cape must be")) return "That cape has an unsupported image size.";
        if (message != null && message.equals("Choose a PNG file.")) return message;
        if (message != null && message.contains("PNG")) return "That file is not a valid cape PNG.";
        return "Could not read that file.";
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() + count > CapeNetwork.MAX_CAPE_BYTES) {
                    throw new IOException("Cape PNG must be at most 24 KiB.");
                }
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void removeCape() {
        if (busy || !canSend()) return;
        if (!hasOwnCape()) {
            status = "You do not have a cape to remove.";
            return;
        }
        try {
            CapeNetwork.clear();
            lastRequestNanos = System.nanoTime();
            busy = true;
            status = "Removing cape...";
        } catch (RuntimeException e) {
            status = "Could not contact this server.";
            NCapes.LOG.warn("Could not send cape removal", e);
        }
    }

    private boolean canSend() {
        if (!CapeNetwork.isServerAvailable()) {
            status = "This server does not support NCapes.";
            return false;
        }
        long now = System.nanoTime();
        if (lastRequestNanos != 0 && now - lastRequestNanos < ACTION_INTERVAL_NANOS) {
            status = "Wait 3 seconds before changing your cape again.";
            return false;
        }
        return true;
    }
}
