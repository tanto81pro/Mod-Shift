package com.tanto.modshift.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/** Settings file: config/modshift.properties */
public final class Config {
	public static boolean includeBeta = false;
	public static boolean includeAlpha = false;
	public static boolean checkOnStartup = true;
	public static boolean addDependencies = true;
	public static boolean nameSearch = true;
	/** CurseForge: either an API key (used directly) or the address of the user's own proxy Worker. */
	public static String cfKey = "";
	public static String cfProxy = "";
	/** Suggestions the user hid with the × button. */
	public static final Set<String> dismissed = new LinkedHashSet<>();
	/** Projects the user never wants ModShift to update. */
	public static final Set<String> ignored = new LinkedHashSet<>();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("modshift.properties");
	}

	public static boolean curseForgeEnabled() {
		return !cfKey.isBlank() || !cfProxy.isBlank();
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
		nameSearch = Boolean.parseBoolean(p.getProperty("nameSearch", "true"));
		cfKey = p.getProperty("curseforgeApiKey", "").trim();
		cfProxy = p.getProperty("curseforgeProxy", "").trim();
		readSet(p.getProperty("dismissedSuggestions", ""), dismissed);
		readSet(p.getProperty("ignoredProjects", ""), ignored);
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("includeBeta", String.valueOf(includeBeta));
		p.setProperty("includeAlpha", String.valueOf(includeAlpha));
		p.setProperty("checkOnStartup", String.valueOf(checkOnStartup));
		p.setProperty("addDependencies", String.valueOf(addDependencies));
		p.setProperty("nameSearch", String.valueOf(nameSearch));
		p.setProperty("curseforgeApiKey", cfKey);
		p.setProperty("curseforgeProxy", cfProxy);
		p.setProperty("dismissedSuggestions", String.join(",", dismissed));
		p.setProperty("ignoredProjects", String.join(",", ignored));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				p.store(w, "ModShift settings. Keep curseforgeApiKey private.");
			}
		} catch (IOException e) {
			ModShiftClient.LOG.warn("Could not save ModShift settings", e);
		}
	}

	private static void readSet(String s, Set<String> into) {
		into.clear();
		Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(into::add);
	}

	private Config() {}
}
