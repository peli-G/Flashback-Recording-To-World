package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.config.FbrGameRuleConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class FbrGameRuleScreen extends EditGameRulesScreen {

    private final Screen fbr$parentScreen;

    private FbrGameRuleScreen(GameRules gameRules, Screen parentScreen, Consumer<Optional<GameRules>> exitCallback) {
        super(gameRules, exitCallback);
        this.fbr$parentScreen = parentScreen;
    }

    public static Screen create(Screen parent) {
        return build(parent, FbrGameRuleConfig.load());
    }

    private static Screen build(Screen parent, Map<String, String> startingValues) {
        GameRules gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        applyValuesToGameRules(gameRules, startingValues);

        return new FbrGameRuleScreen(gameRules, parent, optionalRules -> {
            if (optionalRules.isPresent()) {
                Map<String, String> saved = extractValuesFromGameRules(optionalRules.get());
                FbrGameRuleConfig.save(saved);
            }
            net.minecraft.client.Minecraft.getInstance().setScreen(parent);
        });
    }

    @Override
    protected void init() {
        super.init();
        relabelCancelToReset();
    }

    private void relabelCancelToReset() {
        Button cancelButton = null;
        for (var widget : this.children()) {
            if (widget instanceof Button button
                    && button.getMessage().getString().equalsIgnoreCase("Cancel")) {
                cancelButton = button;
                break;
            }
        }
        if (cancelButton == null) return;

        int x = cancelButton.getX(), y = cancelButton.getY();
        int w = cancelButton.getWidth(), h = cancelButton.getHeight();
        this.removeWidget(cancelButton);

        Screen capturedParent = this.fbr$parentScreen;
        Button resetButton = Button.builder(Component.literal("Reset"), btn ->
                this.minecraft.setScreen(build(capturedParent, FbrGameRuleConfig.builtInDefaults())))
                .bounds(x, y, w, h).build();
        this.addRenderableWidget(resetButton);
    }

    private static void applyValuesToGameRules(GameRules gameRules, Map<String, String> values) {
        gameRules.availableRules().forEach(rule -> {
            String id = "minecraft:" + rule.id();
            String stored = values.get(id);
            if (stored != null) {
                rule.deserialize(stored).result().ifPresent(value ->
                        gameRules.set(castRule(rule), value, null));
            }
        });
    }

    private static Map<String, String> extractValuesFromGameRules(GameRules gameRules) {
        Map<String, String> result = new LinkedHashMap<>();
        gameRules.availableRules().forEach(rule -> {
            String id = "minecraft:" + rule.id();
            result.put(id, serializeRule(gameRules, rule));
        });
        return result;
    }

    private static <T> String serializeRule(GameRules gameRules, GameRule<T> rule) {
        T value = gameRules.get(rule);
        return rule.serialize(value);
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Object> castRule(GameRule<?> rule) {
        return (GameRule<Object>) rule;
    }
}
