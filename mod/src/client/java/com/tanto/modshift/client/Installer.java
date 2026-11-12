package com.tanto.modshift.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import net.fabricmc.loader.api.FabricLoader;

/** Downloads the chosen files, writes the swap plan and starts the helper process. */
public final class Installer {
	public static Path workDir() {
		// Outside the mods folder on purpose: some loaders also scan sub folders of mods.
		return FabricLoader.getInstance().getGameDir().resolve("modshift");
	}

	public static Path planFile() {
		return workDir().resolve("pending.plan");
	}

	public static boolean hasPendingPlan() {
		return Files.exists(planFile());
	}

	/**
	 * Downloads every selected file into the staging folder and checks its SHA-1.
	 * @param progress called with (done, total)
	 */
	public static void download(List<UpdateChecker.Update> updates, BiConsumer<Integer, Integer> progress)
			throws IOException, InterruptedException {
		Path staging = workDir().resolve("staging");
		Files.createDirectories(staging);
		List<String> plan = new ArrayList<>();
		Path mods = UpdateChecker.modsDir().toAbsolutePath();
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		plan.add("MODS\t" + mods);
		plan.add("BACKUP\t" + workDir().toAbsolutePath().resolve("backup").resolve(stamp));

		int done = 0;
		for (UpdateChecker.Update u : updates) {
			progress.accept(done, updates.size());
			if (!u.url.startsWith("https://cdn.modrinth.com/")) {
				throw new IOException("Refusing to download from an unknown host: " + u.url);
			}
			Path target = staging.resolve(u.sha1 + ".jar");
			if (!Files.exists(target) || !UpdateChecker.sha1(target).equalsIgnoreCase(u.sha1)) {
				Path tmp = staging.resolve(u.sha1 + ".part");
				HttpRequest req = HttpRequest.newBuilder(URI.create(u.url)).header("User-Agent", UpdateChecker.USER_AGENT)
						.timeout(Duration.ofMinutes(5)).GET().build();
				HttpResponse<Path> r = UpdateChecker.HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
				if (r.statusCode() / 100 != 2) {
					Files.deleteIfExists(tmp);
					throw new IOException(u.fileName + ": download failed (" + r.statusCode() + ")");
				}
				if (!UpdateChecker.sha1(tmp).equalsIgnoreCase(u.sha1)) {
					Files.deleteIfExists(tmp);
					throw new IOException(u.fileName + ": file check failed, the download was damaged");
				}
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			if (u.oldFile != null) plan.add("DEL\t" + u.oldFile.toAbsolutePath());
			plan.add("ADD\t" + target.toAbsolutePath() + "\t" + safeName(u.fileName));
			done++;
		}
		progress.accept(done, updates.size());
		Files.write(planFile(), plan, StandardCharsets.UTF_8);
	}

	/** Copies the helper out of the mods folder and starts it. The game must be closed right after. */
	public static void launchHelper() throws IOException {
		List<Path> origin = FabricLoader.getInstance().getModContainer("modshift").orElseThrow()
				.getOrigin().getPaths();
		Files.createDirectories(workDir());
		String classpath;
		if (origin.size() == 1 && Files.isRegularFile(origin.get(0))) {
			// Copy the jar so the helper keeps running even if ModShift itself gets replaced.
			Path helperJar = workDir().resolve("helper.jar");
			Files.copy(origin.get(0), helperJar, StandardCopyOption.REPLACE_EXISTING);
			classpath = helperJar.toAbsolutePath().toString();
		} else {
			// Development environment: classes live in folders.
			classpath = String.join(java.io.File.pathSeparator, origin.stream().map(p -> p.toAbsolutePath().toString()).toList());
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(javaExecutable());
		cmd.add("-Xmx64m");
		cmd.add("-cp");
		cmd.add(classpath);
		cmd.add("com.tanto.modshift.helper.SwapHelper");
		cmd.add(String.valueOf(ProcessHandle.current().pid()));
		cmd.add(planFile().toAbsolutePath().toString());

		ProcessBuilder pb = new ProcessBuilder(cmd)
				.directory(workDir().toFile())
				.redirectErrorStream(true)
				.redirectOutput(workDir().resolve("helper-output.log").toFile());
		pb.environment().remove("JAVA_TOOL_OPTIONS");
		pb.start();
	}

	private static String javaExecutable() {
		Path home = Path.of(System.getProperty("java.home"), "bin");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (windows) {
			// javaw does not open a console window.
			Path javaw = home.resolve("javaw.exe");
			if (Files.exists(javaw)) return javaw.toString();
			return home.resolve("java.exe").toString();
		}
		return ProcessHandle.current().info().command()
				.filter(c -> c.endsWith("java"))
				.orElse(home.resolve("java").toString());
	}

	private static String safeName(String name) {
		String n = Path.of(name).getFileName().toString().replaceAll("[\\\\/:*?\"<>|\\t]", "_");
		return n.toLowerCase(Locale.ROOT).endsWith(".jar") ? n : n + ".jar";
	}

	private Installer() {}
}
