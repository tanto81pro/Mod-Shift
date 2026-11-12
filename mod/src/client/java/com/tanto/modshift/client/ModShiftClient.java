package com.tanto.modshift.client;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class ModShiftClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("ModShift");

	/** Result of the most recent check, shared by the title screen button and the ModShift screen. */
	static volatile UpdateChecker.Result lastResult;
	static volatile boolean checking;
	static volatile String lastError;

	@Override
	public void onInitializeClient() {
		Config.load();
		if (Config.checkOnStartup) startCheck(null);

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof TitleScreen)) return;
			Button b = Button.builder(buttonLabel(), btn -> client.setScreenAndShow(new ModShiftScreen(screen)))
					.bounds(4, 4, 96, 20)
					.tooltip(Tooltip.create(Component.translatable("modshift.button.tooltip")))
					.build();
			Screens.getWidgets(screen).add(b);
			// The startup check may finish while the title screen is open: keep the label fresh.
			ScreenEvents.afterTick(screen).register(s -> b.setMessage(buttonLabel()));
		});
	}

	static Component buttonLabel() {
		if (Installer.hasPendingPlan()) return Component.translatable("modshift.button.pending");
		UpdateChecker.Result r = lastResult;
		if (r != null && !r.updates.isEmpty()) {
			return Component.translatable("modshift.button.count", r.updates.size());
		}
		return Component.literal("ModShift");
	}

	/** Runs a check in the background. onDone runs on the game thread. */
	static void startCheck(Runnable onDone) {
		if (checking) return;
		checking = true;
		lastError = null;
		CompletableFuture.runAsync(() -> {
			try {
				lastResult = UpdateChecker.check();
			} catch (Exception e) {
				lastError = e.getMessage() == null ? e.toString() : e.getMessage();
				LOG.warn("ModShift update check failed", e);
			} finally {
				checking = false;
				Minecraft mc = Minecraft.getInstance();
				if (onDone != null && mc != null) mc.execute(onDone);
			}
		});
	}

	static String version() {
		return FabricLoader.getInstance().getModContainer("modshift")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.0.0");
	}
}
