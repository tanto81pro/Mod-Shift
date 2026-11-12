package com.tanto.modshift.client;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

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

	/** Downloads and checks every file, then writes the plan. Returns the staged jars. */
	public static List<Path> prepare(List<UpdateChecker.Entry> chosen, BiConsumer<Integer, Integer> progress)
			throws IOException, InterruptedException {
		Path staging = workDir().resolve("staging");
		Files.createDirectories(staging);
		Path mods = UpdateChecker.modsDir().toAbsolutePath();
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		List<String> plan = new ArrayList<>();
		plan.add("MODS\t" + mods);
		plan.add("BACKUP\t" + workDir().toAbsolutePath().resolve("backup").resolve(stamp));
		List<Path> staged = new ArrayList<>();

		List<UpdateChecker.Entry> downloads = chosen.stream().filter(UpdateChecker.Entry::downloads).toList();
		int done = 0;
		for (UpdateChecker.Entry e : downloads) {
			progress.accept(done, downloads.size());
			if (e.url == null || !Net.trustedDownload(e.url)) {
				throw new IOException(e.title + ": refusing to download from " + e.url);
			}
			Path target = staging.resolve(e.sha1 + ".jar");
			if (!Files.exists(target) || !JarInfo.sha1(target).equalsIgnoreCase(e.sha1)) {
				Path tmp = staging.resolve(e.sha1 + ".part");
				HttpResponse<Path> r = Net.HTTP.send(Net.downloadRequest(e.url), HttpResponse.BodyHandlers.ofFile(tmp));
				if (r.statusCode() / 100 != 2) {
					Files.deleteIfExists(tmp);
					throw new IOException(e.title + ": download failed (" + r.statusCode() + ")");
				}
				if (!JarInfo.sha1(tmp).equalsIgnoreCase(e.sha1)) {
					Files.deleteIfExists(tmp);
					throw new IOException(e.title + ": file check failed, the download was damaged");
				}
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			staged.add(target);
			if (e.oldFile != null) plan.add("DEL\t" + e.oldFile.toAbsolutePath());
			plan.add("ADD\t" + target.toAbsolutePath() + "\t" + safeName(e.fileName));
			done++;
		}
		for (UpdateChecker.Entry e : chosen) {
			if (e.kind == UpdateChecker.Kind.REMOVE && e.oldFile != null) plan.add("DEL\t" + e.oldFile.toAbsolutePath());
		}
		progress.accept(done, downloads.size());
		Files.write(planFile(), plan, StandardCharsets.UTF_8);
		return staged;
	}

	/** Loader versions the new files ask for that the installed loader does not meet. Empty when all is fine. */
	public static List<String> loaderProblems(List<Path> staged) {
		List<String> out = new ArrayList<>();
		Version installed = FabricLoader.getInstance().getModContainer("fabricloader")
				.map(c -> c.getMetadata().getVersion()).orElse(null);
		if (installed == null || UpdateChecker.quilt()) return out;
		String strongest = null;
		for (Path p : staged) {
			try {
				JarInfo j = JarInfo.read(p, false);
				if (j.loaderRequirement == null) continue;
				if (!VersionPredicate.parse(j.loaderRequirement).test(installed)) {
					String need = j.loaderRequirement.replaceAll("[^0-9.]+", " ").trim().split(" ")[0];
					if (strongest == null || Version.parse(need).compareTo(Version.parse(strongest)) > 0) strongest = need;
					out.add((j.name != null ? j.name : p.getFileName()) + ": " + j.loaderRequirement);
				}
			} catch (Exception ignored) {
			}
		}
		if (strongest != null) out.add(0, strongest + "|" + installed.getFriendlyString());
		return out;
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
