package com.moulberry.fbrtoworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

public class FbrSortDirectionButton extends Button {

    private static final Identifier DOWN_ARROW = Identifier.fromNamespaceAndPath("fbr-to-world", "down_arrow.png");
    private static final Identifier UP_ARROW = Identifier.fromNamespaceAndPath("fbr-to-world", "up_arrow.png");

    public boolean sortDescending;

    public FbrSortDirectionButton(int x, int y, int width, int height, Component component, Consumer<Boolean> changed, boolean initialSortDescending) {
        super(x, y, width, height, component, button -> {
            FbrSortDirectionButton btn = (FbrSortDirectionButton) button;
            btn.sortDescending = !btn.sortDescending;
            changed.accept(btn.sortDescending);
        }, DEFAULT_NARRATION);
        this.sortDescending = initialSortDescending;
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderDefaultSprite(guiGraphics);

        final int size = 16;
        int x = this.getX() + (this.getWidth() - size) / 2;
        int y = this.getY() + (this.getHeight() - size) / 2;

        Identifier icon = this.sortDescending ? DOWN_ARROW : UP_ARROW;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0f, 0f, size, size, size, size);
    }
}
