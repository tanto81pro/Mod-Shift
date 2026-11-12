package com.tanto.modshift.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Tiny settings file: config/modshift.properties */
public final class Config {
	public static boolean includeBeta = false;
	public static boolean includeAlpha = false;
	public static boolean checkOnStartup = true;
	public static boolean addDependencies = true;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("modshift.properties");
	}

	public static void load() {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file())) {
			p.load(r);
		} catch (IOException ignored) {
			return;
		}
		includeBeta = Boolean.parseBoolean(p.getProperty("includeBeta", "false"));
		includeAlpha = Boolean.parseBoolean(p.getProperty("includeAlpha", "false"));
		checkOnStartup = Boolean.parseBoolean(p.getProperty("checkOnStartup", "true"));
		addDependencies = Boolean.parseBoolean(p.getProperty("addDependencies", "true"));
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("includeBeta", String.valueOf(includeBeta));
		p.setProperty("includeAlpha", String.valueOf(includeAlpha));
		p.setProperty("checkOnStartup", String.valueOf(checkOnStartup));
		p.setProperty("addDependencies", String.valueOf(addDependencies));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				p.store(w, "ModShift settings");
			}
		} catch (IOException e) {
			ModShiftClient.LOG.warn("Could not save ModShift settings", e);
		}
	}

	private Config() {}
}
