package com.moulberry.fbrtoworld.mixin.ui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = TitleScreen.class, priority = 1400)
public abstract class MixinTitleScreen extends Screen {

    private MixinTitleScreen() {
        super(null);
    }

    @Unique
    private static final boolean FLASHBACK_INSTALLED =
            FabricLoader.getInstance().isModLoaded("flashback");

    @Unique
    private static Class<?> fbr$flashbackButtonClass;
    static {
        if (FLASHBACK_INSTALLED) {
            try {
                fbr$flashbackButtonClass = Class.forName("com.moulberry.flashback.screen.FlashbackButton");
            } catch (ClassNotFoundException e) {
                fbr$flashbackButtonClass = null;
            }
        }
    }

    @Unique
    private final List<AbstractWidget> fbr$normalMenuWidgets = new ArrayList<>();

    @Unique
    private AbstractWidget fbr$button = null;

    @Unique
    private boolean fbr$checkedPosition = false;

    @Inject(method = "init", at = @At("RETURN"))
    private void fbr$onInit(CallbackInfo ci) {
        this.fbr$createOrPositionButton(true);
        this.fbr$checkedPosition = false;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fbr$onTick(CallbackInfo ci) {
        if (!this.fbr$checkedPosition) {
            this.fbr$checkedPosition = true;
            this.fbr$createOrPositionButton(false);
        }
    }

    @Unique
    private boolean fbr$isWidgetPresent(AbstractWidget widget) {
        for (GuiEventListener listener : this.children()) {
            if (listener == widget) return true;
        }
        return false;
    }

    @Unique
    private AbstractWidget fbr$findFlashbackButton() {
        if (!FLASHBACK_INSTALLED || fbr$flashbackButtonClass == null) return null;
        for (GuiEventListener listener : this.children()) {
            if (fbr$flashbackButtonClass.isInstance(listener) && listener != this.fbr$button) {
                return (AbstractWidget) listener;
            }
        }
        return null;
    }

    @Unique
    private void fbr$createOrPositionButton(boolean allowCreating) {
        if (this.fbr$button != null && !fbr$isWidgetPresent(this.fbr$button)) {
            this.fbr$button = null;
        }
        if (!allowCreating && this.fbr$button == null) {
            return;
        }

        if (this.fbr$button != null && !fbr$wouldOverlap(
                this.fbr$button.getX(), this.fbr$button.getY(),
                this.fbr$button.getWidth(), this.fbr$button.getHeight())) {
            return;
        }

        AbstractWidget flashbackButton = this.fbr$findFlashbackButton();

        if (flashbackButton != null) {

            int size = flashbackButton.getHeight();
            for (int offsetX = 0; offsetX < 5; offsetX++) {
                int x = flashbackButton.getRight() + 4 + size * offsetX;
                int y = flashbackButton.getY();
                if (!fbr$wouldOverlap(x, y, size, size)) {
                    fbr$placeButton(x, y, size);
                    return;
                }
            }
        }

        for (int offsetX = 0; offsetX < 5; offsetX++) {
            for (int i = this.fbr$normalMenuWidgets.size() - 1; i >= 0; i--) {
                AbstractWidget widget = this.fbr$normalMenuWidgets.get(i);
                if (!fbr$isWidgetPresent(widget)) continue;

                int size = widget.getHeight();
                int x = widget.getRight() + 4 + size * offsetX;
                int y = widget.getY();

                if (!fbr$wouldOverlap(x, y, size, size)) {
                    fbr$placeButton(x, y, size);
                    return;
                }
            }
        }
    }

    @Unique
    private static final net.minecraft.resources.Identifier FBR_ICON =
            net.minecraft.resources.Identifier.parse("fbr-to-world:icon_pixelated.png");

    @Unique
    private void fbr$placeButton(int x, int y, int size) {
        if (this.fbr$button == null) {
            this.fbr$button = new com.moulberry.fbrtoworld.screen.FbrIconButton(
                    x, y, size, size, Component.literal("FBR to World"), btn -> fbr$onButtonPressed(), FBR_ICON);
            this.addRenderableWidget(this.fbr$button);
        } else {
            this.fbr$button.setX(x);
            this.fbr$button.setY(y);
        }
    }

    @Unique
    private void fbr$onButtonPressed() {
        com.moulberry.fbrtoworld.FbrToWorldMod.LOGGER.info(
                "[FbrToWorld] Title screen button pressed — opening FbrSelectReplayScreen "
                + "(connection={}, level={}, cachedRegistry={})",
                this.minecraft.getConnection() != null, this.minecraft.level != null,
                com.moulberry.fbrtoworld.FbrToWorldMod.cachedRegistryAccess != null);

        this.minecraft.setScreen(new com.moulberry.fbrtoworld.screen.FbrSelectReplayScreen(this));
    }

    @Unique
    private boolean fbr$wouldOverlap(int x, int y, int w, int h) {
        for (GuiEventListener listener : this.children()) {
            if (listener == this.fbr$button) continue;
            if (listener instanceof AbstractWidget other) {
                if (x < other.getRight() && x + w > other.getX()
                        && y < other.getBottom() && y + h > other.getY()) {
                    return true;
                }
            }
        }
        return false;
    }

    @WrapOperation(method = "createNormalMenuOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/TitleScreen;addRenderableWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;"))
    private GuiEventListener fbr$trackNormalMenuOption(TitleScreen instance, GuiEventListener listener, Operation<GuiEventListener> original) {
        GuiEventListener result = original.call(instance, listener);
        if (result instanceof AbstractWidget widget) {
            this.fbr$normalMenuWidgets.add(widget);
        }
        return result;
    }

    @Inject(method = "createNormalMenuOptions", at = @At("HEAD"))
    private void fbr$clearNormalMenuWidgets(int i, int j, CallbackInfoReturnable<Integer> cir) {
        this.fbr$normalMenuWidgets.clear();
    }
}
