package com.moulberry.fbrtoworld.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Standalone equivalent of Flashback's ReplaySelectionEntry — renders one row
 * in the replay browser list: thumbnail/folder icon, name, date, info, and a
 * join/error/warning overlay on hover.
 */
public abstract class FbrReplaySelectionEntry extends ObjectSelectionList.Entry<FbrReplaySelectionEntry> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("fbr-to-world");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
    private static final Identifier FOLDER_SPRITE = Identifier.fromNamespaceAndPath("fbr-to-world", "folder");
    private static final Identifier WARNING_SPRITE = Identifier.withDefaultNamespace("world_list/warning");
    private static final Identifier WARNING_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("world_list/warning_highlighted");
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("world_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("world_list/join_highlighted");

    @Override
    public void close() {}

    public static class LoadingHeader extends FbrReplaySelectionEntry {
        private static final Component LOADING_LABEL = Component.literal("Loading replays...");
        private final Minecraft minecraft;

        public LoadingHeader(Minecraft minecraft) { this.minecraft = minecraft; }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int p = (this.minecraft.screen.width - this.minecraft.font.width(LOADING_LABEL)) / 2;
            int q = this.getContentY() + (this.getContentHeight() - this.minecraft.font.lineHeight) / 2;
            guiGraphics.drawString(this.minecraft.font, LOADING_LABEL, p, q, 0xFFFFFFFF, false);
            String dots = LoadingDotsText.get(Util.getMillis());
            int r = (this.minecraft.screen.width - this.minecraft.font.width(dots)) / 2;
            guiGraphics.drawString(this.minecraft.font, dots, r, q + this.minecraft.font.lineHeight, 0xFF808080, false);
        }

        @Override
        public Component getNarration() { return LOADING_LABEL; }
    }

    public static class ReplayFolder extends FbrReplaySelectionEntry {
        private static final int ICON = 32;
        private final Minecraft minecraft;
        private final FbrReplaySelectionList list;
        private final Path path;
        private final Component nameComponent;
        private final long modifiedTime;
        private final int replayCount;
        private long lastClickTime;

        public ReplayFolder(FbrReplaySelectionList list, Minecraft minecraft, Path path, long modifiedTime, int replayCount) {
            this.minecraft = minecraft;
            this.list = list;
            this.path = path;
            this.nameComponent = Component.literal(path.getFileName().toString()).withStyle(ChatFormatting.BOLD);
            this.modifiedTime = modifiedTime;
            this.replayCount = replayCount;
        }

        @Override
        public Component getNarration() { return this.nameComponent; }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = this.getContentX(), y = this.getContentY();
            guiGraphics.drawString(this.minecraft.font, this.nameComponent, x + ICON + 3, y + 1, -1, false);

            int textY = y + this.minecraft.font.lineHeight + 2;
            if (this.modifiedTime > 0) {
                String time = "Created: " + DATE_FORMAT.format(Instant.ofEpochMilli(this.modifiedTime));
                guiGraphics.drawString(this.minecraft.font, time, x + ICON + 3, textY + 1, 0xFF808080, false);
                textY += this.minecraft.font.lineHeight;
            }
            guiGraphics.drawString(this.minecraft.font, "Replays: " + this.replayCount, x + ICON + 3, textY + 3, 0xFF808080, false);

            guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOLDER_SPRITE, x, y, ICON, ICON);

            if (this.minecraft.options.touchscreen().get() || hovered) {
                guiGraphics.fill(x, y, x + ICON, y + ICON, 0xa0909090);
                boolean hoveredIcon = (mouseX - x) < ICON;
                guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                        hoveredIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE, x, y, ICON, ICON);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            this.list.setSelected(this);
            if (event.x() - (double) this.list.getRowLeft() <= 32.0 || Util.getMillis() - this.lastClickTime < 250L) {
                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                this.minecraft.setScreen(new FbrSelectReplayScreen(this.list.getScreen().lastScreen, this.path));
                return true;
            }
            this.lastClickTime = Util.getMillis();
            return super.mouseClicked(event, doubleClick);
        }
    }

    public static class ReplayListEntry extends FbrReplaySelectionEntry {
        private static final int ICON = 32;
        private final Minecraft minecraft;
        private final FbrReplaySelectionList list;
        public final FbrReplaySummary summary;
        private FaviconTexture icon;
        private long lastClickTime;

        public ReplayListEntry(FbrReplaySelectionList list, Minecraft minecraft, FbrReplaySummary summary) {
            this.minecraft = minecraft;
            this.list = list;
            this.summary = summary;
            this.icon = FaviconTexture.forWorld(minecraft.getTextureManager(), summary.getReplayId());
            this.loadIcon();
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.summary.getReplayName() + ", " + this.summary.getInfo().getString());
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = this.getContentX(), y = this.getContentY();

            String title = this.summary.getReplayName();
            String fileAndTime = this.summary.getReplayId();
            long lm = this.summary.getLastModified();
            if (lm != -1L) fileAndTime = fileAndTime + " (" + DATE_FORMAT.format(Instant.ofEpochMilli(lm)) + ")";
            if (StringUtils.isEmpty(title)) title = "Replay";
            Component info = this.summary.getInfo();

            int titleColour = this.summary.hasWarning() ? 0xFFFFAA55 : 0xFFFFFFFF;

            guiGraphics.drawString(this.minecraft.font, title, x + ICON + 3, y + 1, titleColour, false);
            int titleEnd = x + ICON + 3 + this.minecraft.font.width(title);

            String worldName = this.summary.getWorldName();
            if (worldName != null) {
                guiGraphics.drawString(this.minecraft.font, "(" + worldName + ")", titleEnd + 4, y + 1, 0xFF808080, false);
            }

            guiGraphics.drawString(this.minecraft.font, fileAndTime, x + ICON + 3, y + this.minecraft.font.lineHeight + 3, 0xFF808080, false);
            guiGraphics.drawString(this.minecraft.font, info, x + ICON + 3, y + this.minecraft.font.lineHeight * 2 + 3, 0xFF808080, false);

            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.icon.textureLocation(), x, y, 0f, 0f, ICON, ICON, ICON, ICON);

            if (this.minecraft.options.touchscreen().get() || hovered) {
                guiGraphics.fill(x, y, x + ICON, y + ICON, 0xa0909090);
                boolean hoveredIcon = (mouseX - x) < ICON;
                Identifier overlay = this.summary.hasWarning()
                        ? (hoveredIcon ? WARNING_HIGHLIGHTED_SPRITE : WARNING_SPRITE)
                        : (hoveredIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE);
                guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, overlay, x, y, ICON, ICON);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            this.list.setSelected(this);
            if (event.x() - (double) this.list.getRowLeft() <= 32.0 || Util.getMillis() - this.lastClickTime < 250L) {
                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                this.startConversion();
                return true;
            }
            this.lastClickTime = Util.getMillis();
            return super.mouseClicked(event, doubleClick);
        }

        public void startConversion() {
            var connection = this.minecraft.getConnection();
            var level = this.minecraft.level;
            var cached = com.moulberry.fbrtoworld.FbrToWorldMod.cachedRegistryAccess;

            com.moulberry.fbrtoworld.FbrToWorldMod.LOGGER.info(
                    "[FbrToWorld] Convert clicked — connection={}, level={}, cachedRegistryAccess={}",
                    connection != null, level != null, cached != null);

            net.minecraft.core.RegistryAccess registryAccess;
            String source;
            if (connection != null) {
                registryAccess = connection.registryAccess();
                source = "live connection";
            } else if (level != null) {
                registryAccess = level.registryAccess();
                source = "live level";
            } else if (cached != null) {
                registryAccess = cached;
                source = "cached from last session";
            } else {
                registryAccess = null;
                source = "none available";
            }

            com.moulberry.fbrtoworld.FbrToWorldMod.LOGGER.info(
                    "[FbrToWorld] Using registry access source: {}", source);

            if (registryAccess == null) {
                // Conversion needs registries (biomes, block entity types, etc) from
                // a world — these aren't available before joining any world for the
                // very first time this game session.
                this.minecraft.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
                        () -> this.minecraft.setScreen(this.list.getScreen()),
                        net.minecraft.network.chat.Component.literal("Can't Convert Yet"),
                        net.minecraft.network.chat.Component.literal(
                                "Please join any world first (singleplayer or a server) at least once this session, then come back here to convert this replay."),
                        net.minecraft.network.chat.Component.literal("Ok"),
                        true
                ));
                return;
            }

            boolean started = com.moulberry.fbrtoworld.FbrWorldConverter.start(
                    this.summary.getPath(), registryAccess, this.minecraft);
            com.moulberry.fbrtoworld.FbrToWorldMod.LOGGER.info(
                    "[FbrToWorld] FbrWorldConverter.start() returned: {}", started);
            if (started) {
                this.minecraft.setScreen(new FbrConvertProgressScreen(this.list.getScreen()));
            }
        }

        public void deleteReplay() {
            try {
                Files.delete(this.summary.getPath());
            } catch (IOException e) {
                LOGGER.error("Failed to delete replay {}", this.summary.getReplayId(), e);
            }
            this.list.reloadReplayList();
        }

        private void loadIcon() {
            byte[] iconBytes = this.summary.getIconBytes();
            if (iconBytes != null) {
                try {
                    this.icon.upload(NativeImage.read(iconBytes));
                    return;
                } catch (Throwable t) {
                    LOGGER.error("Invalid icon for replay {}", this.summary.getReplayId(), t);
                }
            }
            this.icon.clear();
        }

        @Override
        public void close() {
            if (this.icon != null) {
                this.icon.close();
                this.icon = null;
            }
        }
    }
}
