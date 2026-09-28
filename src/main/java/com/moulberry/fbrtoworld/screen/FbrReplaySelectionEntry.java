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
                this.minecraft.setScreen(new FbrSelectReplayScreen(
                        this.list.getScreen().lastScreen, this.path, this.list.isCombineMode()));
                return true;
            }
            this.lastClickTime = Util.getMillis();
            return super.mouseClicked(event, doubleClick);
        }
    }

    public static class ReplayListEntry extends FbrReplaySelectionEntry {
        private static final int ICON = 32;
        private static final int BOX_SIZE = 14;
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

            if (this.list.isCombineMode()) {
                this.renderCombineBox(guiGraphics, mouseX, mouseY, y);
            }
        }

        private void renderCombineBox(GuiGraphics guiGraphics, int mouseX, int mouseY, int y) {
            int bx = this.boxLeft();
            int by = y + (ICON - BOX_SIZE) / 2;
            int order = this.list.getCombineOrder(this.summary.getPath());
            boolean hoveredBox = mouseX >= bx - 2 && mouseX <= bx + BOX_SIZE + 2
                    && mouseY >= by - 2 && mouseY <= by + BOX_SIZE + 2;

            guiGraphics.fill(bx - 3, by - 3, bx + BOX_SIZE + 3, by + BOX_SIZE + 3, 0xC0000000);

            int border = order > 0 ? 0xFF55FF55 : (hoveredBox ? 0xFFFFFFFF : 0xFF909090);
            guiGraphics.fill(bx, by, bx + BOX_SIZE, by + 1, border);
            guiGraphics.fill(bx, by + BOX_SIZE - 1, bx + BOX_SIZE, by + BOX_SIZE, border);
            guiGraphics.fill(bx, by, bx + 1, by + BOX_SIZE, border);
            guiGraphics.fill(bx + BOX_SIZE - 1, by, bx + BOX_SIZE, by + BOX_SIZE, border);
            guiGraphics.fill(bx + 1, by + 1, bx + BOX_SIZE - 1, by + BOX_SIZE - 1, 0xFF101010);

            if (order > 0) {
                String label = String.valueOf(order);
                int tx = bx + (BOX_SIZE - this.minecraft.font.width(label)) / 2;
                int ty = by + (BOX_SIZE - this.minecraft.font.lineHeight) / 2 + 1;
                guiGraphics.drawString(this.minecraft.font, label, tx, ty, 0xFF55FF55, false);
            }
        }

        private int boxLeft() {
            return this.getContentX() - FbrReplaySelectionList.CHECKBOX_GUTTER + 3;
        }

        private boolean isOverCombineBox(double mouseX, double mouseY) {
            int bx = this.boxLeft();
            int by = this.getContentY() + (ICON - BOX_SIZE) / 2;
            return mouseX >= bx - 3 && mouseX <= bx + BOX_SIZE + 3
                    && mouseY >= by - 3 && mouseY <= by + BOX_SIZE + 3;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            this.list.setSelected(this);

            if (this.list.isCombineMode()) {
                if (this.isOverCombineBox(event.x(), event.y())) {
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    this.list.toggleCombine(this.summary.getPath());
                    this.list.setSelected(this);
                    this.lastClickTime = Util.getMillis();
                    return true;
                }
                this.lastClickTime = Util.getMillis();
                return super.mouseClicked(event, doubleClick);
            }

            if (event.x() - (double) this.list.getRowLeft() <= 32.0 || Util.getMillis() - this.lastClickTime < 250L) {
                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                this.list.getScreen().openConvertOptions(this.summary.getPath());
                return true;
            }
            this.lastClickTime = Util.getMillis();
            return super.mouseClicked(event, doubleClick);
        }

        public void deleteReplay() {
            try {
                Files.delete(this.summary.getPath());
            } catch (IOException e) {
                LOGGER.error("Failed to delete replay {}", this.summary.getReplayId(), e);
            }
            this.list.removeFromCombine(this.summary.getPath());
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
