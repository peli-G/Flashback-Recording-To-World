package com.moulberry.fbrtoworld.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

public class FbrWorldSelectionEntry extends ObjectSelectionList.Entry<FbrWorldSelectionEntry> {

    private static final Logger LOGGER = LoggerFactory.getLogger("fbr-to-world");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("world_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("world_list/join_highlighted");
    private static final int ICON = 32;

    private final Minecraft minecraft;
    private final FbrWorldSelectionList list;
    public final Path worldPath;
    public final String worldName;
    private final long lastModified;
    private FaviconTexture icon;

    public FbrWorldSelectionEntry(FbrWorldSelectionList list, Minecraft minecraft, Path worldPath, long lastModified) {
        this.minecraft = minecraft;
        this.list = list;
        this.worldPath = worldPath;
        this.worldName = worldPath.getFileName().toString();
        this.lastModified = lastModified;
        this.icon = FaviconTexture.forWorld(minecraft.getTextureManager(), this.worldName);
        this.loadIcon();
    }

    private void loadIcon() {
        Path iconPath = this.worldPath.resolve("icon.png");
        if (Files.exists(iconPath)) {
            try {
                this.icon.upload(NativeImage.read(Files.readAllBytes(iconPath)));
                return;
            } catch (Throwable t) {
                LOGGER.error("Invalid icon for world {}", this.worldName, t);
            }
        }
        this.icon.clear();
    }

    public boolean matchesFilter(String lowerFilter) {
        return lowerFilter.isEmpty() || this.worldName.toLowerCase(java.util.Locale.ROOT).contains(lowerFilter);
    }

    @Override
    public Component getNarration() { return Component.literal(this.worldName); }

    @Override
    public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
        int x = this.getContentX(), y = this.getContentY();

        guiGraphics.drawString(this.minecraft.font, this.worldName, x + ICON + 3, y + 1, 0xFFFFFFFF, false);
        if (this.lastModified > 0) {
            String time = DATE_FORMAT.format(Instant.ofEpochMilli(this.lastModified));
            guiGraphics.drawString(this.minecraft.font, time, x + ICON + 3, y + this.minecraft.font.lineHeight + 3, 0xFF808080, false);
        }

        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.icon.textureLocation(), x, y, 0f, 0f, ICON, ICON, ICON, ICON);

        if (this.minecraft.options.touchscreen().get() || hovered) {
            guiGraphics.fill(x, y, x + ICON, y + ICON, 0xa0909090);
            Identifier overlay = (mouseX - x) < ICON ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
            guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, overlay, x, y, ICON, ICON);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.list.setSelected(this);
        return super.mouseClicked(event, doubleClick);
    }

    public void close() {
        if (this.icon != null) {
            this.icon.close();
            this.icon = null;
        }
    }
}
