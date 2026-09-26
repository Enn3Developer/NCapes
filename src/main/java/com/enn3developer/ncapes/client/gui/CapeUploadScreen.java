package com.enn3developer.ncapes.client.gui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.enn3developer.ncapes.NCapes;
import com.enn3developer.ncapes.network.CapeImageValidator;
import com.enn3developer.ncapes.network.CapeNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CapeUploadScreen {

    private static final long ACTION_INTERVAL_NANOS = 3_100_000_000L;
    private static volatile CapeUploadScreen active;
    private static long lastRequestNanos;

    private final Path capeFolder;
    private final List<Path> capeFiles = new ArrayList<>();
    private Path selected;
    private volatile String status;

    private CapeUploadScreen() {
        capeFolder = Minecraft.getMinecraft().mcDataDir.toPath()
            .resolve("config")
            .resolve("ncapes")
            .resolve("capes");
        try {
            Files.createDirectories(capeFolder);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(capeFolder)) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && entry.getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".png")) {
                        capeFiles.add(entry);
                    }
                }
            }
            capeFiles.sort(
                Comparator.comparing(
                    path -> path.getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)));
            status = capeFiles.isEmpty() ? "Add PNG files to the folder, then Refresh." : "Select a cape to upload.";
        } catch (IOException e) {
            status = "Could not read the cape folder.";
            NCapes.LOG.warn("Could not read cape folder {}", capeFolder, e);
        }
    }

    public static void open() {
        CapeUploadScreen uploadScreen = new CapeUploadScreen();
        active = uploadScreen;
        ClientGUI.open(uploadScreen.createScreen());
    }

    public static void setUploadResult(boolean success, String message) {
        CapeUploadScreen uploadScreen = active;
        if (uploadScreen != null) {
            uploadScreen.status = (success ? "Done: " : "Failed: ") + message;
        }
    }

    private ModularScreen createScreen() {
        ListWidget<IWidget, ?> files = new ListWidget<>();
        files.widthRel(1f)
            .height(105);
        if (capeFiles.isEmpty()) {
            files.child(new TextWidget<>("No cape PNGs found.").height(16));
        } else {
            for (Path file : capeFiles) {
                files.child(fileButton(file));
            }
        }

        Flow actions = Flow.row()
            .widthRel(1f)
            .height(18);
        actions.child(actionButton("Refresh", 66, CapeUploadScreen::open));
        actions.child(actionButton("Upload selected", 90, this::uploadSelected));
        actions.child(actionButton("Remove cape", 76, this::removeCape));

        ModularPanel panel = ModularPanel.defaultPanel("cape_upload", 248, 190)
            .padding(7)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .child(
                        new TextWidget<>("NCapes: upload your cape").widthRel(1f)
                            .height(14))
                    .child(
                        new TextWidget<>("PNG folder: config/ncapes/capes").widthRel(1f)
                            .height(12))
                    .child(files)
                    .child(actions)
                    .child(
                        new TextWidget<>(IKey.dynamic(() -> status)).widthRel(1f)
                            .height(26)));
        return new ModularScreen(NCapes.MODID, panel) {

            @Override
            public void onClose() {
                super.onClose();
                if (active == CapeUploadScreen.this) {
                    active = null;
                }
            }
        };
    }

    private ButtonWidget<?> fileButton(Path file) {
        String name = file.getFileName()
            .toString();
        String label = name.length() > 34 ? name.substring(0, 31) + "..." : name;
        return new ButtonWidget<>().widthRel(1f)
            .height(17)
            .overlay(IKey.dynamic(() -> (file.equals(selected) ? "> " : "") + label))
            .tooltipBuilder(tooltip -> tooltip.addLine(IKey.str(name)))
            .onMousePressed(button -> {
                if (button != 0) return false;
                selected = file;
                status = "Selected: " + name;
                return true;
            });
    }

    private static ButtonWidget<?> actionButton(String label, int width, Runnable action) {
        return new ButtonWidget<>().size(width, 18)
            .overlay(IKey.str(label))
            .onMousePressed(button -> {
                if (button != 0) return false;
                action.run();
                return true;
            });
    }

    private void uploadSelected() {
        if (selected == null) {
            status = "Select a cape first.";
            return;
        }
        if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) {
            status = "That file is gone. Press Refresh.";
            return;
        }
        try {
            long size = Files.size(selected);
            if (size == 0 || size > CapeNetwork.MAX_CAPE_BYTES) {
                status = "PNG must be at most " + (CapeNetwork.MAX_CAPE_BYTES / 1024) + " KiB.";
                return;
            }
            byte[] bytes = readBounded(selected);
            if (bytes.length == 0) {
                status = "The selected file is empty.";
                return;
            }
            CapeImageValidator.decode(bytes);
            if (!canSend()) {
                return;
            }
            CapeNetwork.upload(bytes);
            lastRequestNanos = System.nanoTime();
            status = "Upload sent. Waiting for server...";
        } catch (IOException e) {
            status = e.getMessage() == null ? "Could not read the selected PNG." : e.getMessage();
            NCapes.LOG.warn("Could not upload cape {}", selected, e);
        } catch (RuntimeException e) {
            status = "Could not send the cape to this server.";
            NCapes.LOG.warn("Could not send cape upload", e);
        }
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() + count > CapeNetwork.MAX_CAPE_BYTES) {
                    throw new IOException("Cape exceeds the upload limit");
                }
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void removeCape() {
        if (!canSend()) {
            return;
        }
        try {
            CapeNetwork.clear();
            lastRequestNanos = System.nanoTime();
            status = "Removal requested. Waiting for server...";
        } catch (RuntimeException e) {
            status = "Could not contact this server.";
            NCapes.LOG.warn("Could not send cape removal", e);
        }
    }

    private boolean canSend() {
        long now = System.nanoTime();
        if (lastRequestNanos != 0 && now - lastRequestNanos < ACTION_INTERVAL_NANOS) {
            status = "Wait 3 seconds before changing your cape again.";
            return false;
        }
        return true;
    }
}
