package com.tanto.modshift.helper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs in its own small Java process after the game has been asked to close.
 * Waits for the game process to exit (so Windows releases the jar locks),
 * then applies the plan: old jars go to a backup folder, new jars go into mods.
 *
 * This class must not reference any Minecraft or Fabric classes.
 *
 * Usage: SwapHelper <gamePid> <planFile>
 *
 * Plan file format (UTF-8, one operation per line, tab separated):
 *   MODS    <absolute mods folder>
 *   BACKUP  <absolute backup folder>
 *   DEL     <absolute path of old jar>
 *   ADD     <absolute path of downloaded jar>   <target file name>
 */
public final class SwapHelper {
	private SwapHelper() {}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: SwapHelper <pid> <planFile>");
			System.exit(2);
		}
		long pid = Long.parseLong(args[0]);
		Path plan = Path.of(args[1]);
		Path log = plan.resolveSibling("last-update.log");
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8))) {
			out.println("ModShift update " + LocalDateTime.now());
			waitForExit(pid, out);
			int failures = apply(plan, out);
			if (failures == 0) {
				Files.deleteIfExists(plan);
				out.println("Done. All files were swapped.");
			} else {
				out.println("Finished with " + failures + " problem(s). The plan file was kept so ModShift can retry.");
			}
		}
	}

	static void waitForExit(long pid, PrintWriter out) throws InterruptedException {
		Optional<ProcessHandle> game = ProcessHandle.of(pid);
		if (game.isPresent()) {
			out.println("Waiting for the game (pid " + pid + ") to close...");
			out.flush();
			try {
				game.get().onExit().get();
			} catch (Exception e) {
				while (game.get().isAlive()) Thread.sleep(500);
			}
		}
		// Give the OS a moment to release file handles.
		Thread.sleep(1500);
	}

	/** Applies the plan and returns the number of failed operations. */
	public static int apply(Path plan, PrintWriter out) throws IOException, InterruptedException {
		List<String> lines = Files.readAllLines(plan, StandardCharsets.UTF_8);
		Path mods = null;
		Path backup = null;
		List<String[]> ops = new ArrayList<>();
		for (String line : lines) {
			if (line.isBlank()) continue;
			String[] p = line.split("\t");
			switch (p[0]) {
				case "MODS" -> mods = Path.of(p[1]);
				case "BACKUP" -> backup = Path.of(p[1]);
				default -> ops.add(p);
			}
		}
		if (mods == null) throw new IOException("Plan has no MODS line");
		if (backup == null) {
			backup = mods.resolveSibling("modshift").resolve("backup")
					.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		}
		Files.createDirectories(backup);

		int failures = 0;
		List<String> remaining = new ArrayList<>();
		remaining.add("MODS\t" + mods);
		remaining.add("BACKUP\t" + backup);

		// Remove old files first so a new file can reuse the same name.
		for (String[] op : ops) {
			if (!op[0].equals("DEL")) continue;
			Path src = Path.of(op[1]);
			if (!Files.exists(src)) {
				out.println("skip (already gone): " + src.getFileName());
				continue;
			}
			if (move(src, uniqueTarget(backup.resolve(src.getFileName().toString())), out)) {
				out.println("backed up: " + src.getFileName());
			} else {
				failures++;
				remaining.add(String.join("\t", op));
			}
		}
		for (String[] op : ops) {
			if (!op[0].equals("ADD")) continue;
			Path src = Path.of(op[1]);
			Path dst = mods.resolve(op[2]);
			if (!Files.exists(src)) {
				if (Files.exists(dst)) {
					out.println("skip (already installed): " + op[2]);
				} else {
					out.println("MISSING download: " + src);
					failures++;
				}
				continue;
			}
			if (Files.exists(dst)) {
				// Same name already present: back it up instead of overwriting blindly.
				move(dst, uniqueTarget(backup.resolve(dst.getFileName().toString())), out);
			}
			if (move(src, dst, out)) {
				out.println("installed: " + op[2]);
			} else {
				failures++;
				remaining.add(String.join("\t", op));
			}
		}
		if (failures > 0) Files.write(plan, remaining, StandardCharsets.UTF_8);
		return failures;
	}

	private static boolean move(Path src, Path dst, PrintWriter out) throws InterruptedException {
		IOException last = null;
		for (int i = 0; i < 40; i++) {
			try {
				Files.createDirectories(dst.getParent());
				try {
					Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicFailed) {
					Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
				}
				return true;
			} catch (IOException e) {
				last = e;
				Thread.sleep(500); // file may still be locked for a moment
			}
		}
		out.println("FAILED " + src + " -> " + dst + ": " + last);
		return false;
	}

	private static Path uniqueTarget(Path p) {
		if (!Files.exists(p)) return p;
		String name = p.getFileName().toString();
		for (int i = 1; ; i++) {
			Path c = p.resolveSibling(name + "." + i);
			if (!Files.exists(c)) return c;
		}
	}
}
