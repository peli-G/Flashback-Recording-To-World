package com.moulberry.fbrtoworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A 16x16 pixelated-icon title-screen button, styled the same way as Flashback's
 * own FlashbackButton. Place the icon at:
 * src/main/resources/assets/fbr-to-world/icon_pixelated.png (16x16)
 */
public class FbrIconButton extends Button {

    private final Identifier icon;

    public FbrIconButton(int x, int y, int width, int height, Component component, OnPress onPress, Identifier icon) {
        super(x, y, width, height, component, onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.setTooltip(Tooltip.create(component));
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderDefaultSprite(guiGraphics);

        final int size = 16;
        int paddingX = (this.getWidth() - size) / 2;
        int paddingY = (this.getHeight() - size) / 2;

        int x = this.getX() + paddingX;
        int y = this.getY() + paddingY;

        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.icon, x, y, 0f, 0f, size, size, size, size);
    }
}
