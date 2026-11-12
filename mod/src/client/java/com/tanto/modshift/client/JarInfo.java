package com.tanto.modshift.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** What ModShift knows about one jar in the mods folder. */
public final class JarInfo {
	public final Path path;
	public String sha1;
	public long murmur;          // CurseForge fingerprint
	public boolean broken;       // not a readable zip
	public String loader;        // fabric, quilt, neoforge, forge or null
	public String modId;
	public String name;
	public String version;
	public String loaderRequirement;           // e.g. ">=0.17.2"
	public final List<String> depends = new ArrayList<>();   // mod ids this jar depends on / recommends
	public final List<String> provides = new ArrayList<>();

	private JarInfo(Path path) {
		this.path = path;
	}

	public String fileName() {
		return path.getFileName().toString();
	}

	public static JarInfo read(Path p, boolean withMurmur) throws IOException {
		JarInfo j = new JarInfo(p);
		byte[] bytes = Files.readAllBytes(p);
		j.sha1 = sha1(bytes);
		if (withMurmur) j.murmur = curseForgeFingerprint(bytes);
		try (ZipFile zip = new ZipFile(p.toFile())) {
			String s;
			if ((s = text(zip, "fabric.mod.json")) != null) {
				j.loader = "fabric";
				JsonObject o = JsonParser.parseString(s).getAsJsonObject();
				j.modId = str(o, "id");
				j.name = o.has("name") ? str(o, "name") : j.modId;
				j.version = str(o, "version");
				for (String key : new String[] {"depends", "recommends", "suggests"}) {
					if (o.has(key) && o.get(key).isJsonObject()) {
						JsonObject d = o.getAsJsonObject(key);
						j.depends.addAll(d.keySet());
						if (key.equals("depends") && d.has("fabricloader")) j.loaderRequirement = joinSpec(d.get("fabricloader"));
					}
				}
				if (o.has("provides") && o.get("provides").isJsonArray()) {
					o.getAsJsonArray("provides").forEach(e -> { if (e.isJsonPrimitive()) j.provides.add(e.getAsString()); });
				}
			} else if ((s = text(zip, "quilt.mod.json")) != null) {
				j.loader = "quilt";
				JsonObject q = JsonParser.parseString(s).getAsJsonObject().getAsJsonObject("quilt_loader");
				if (q != null) {
					j.modId = str(q, "id");
					j.version = str(q, "version");
					JsonObject meta = q.getAsJsonObject("metadata");
					j.name = meta != null && meta.has("name") ? str(meta, "name") : j.modId;
					if (q.has("depends")) {
						for (JsonElement d : q.getAsJsonArray("depends")) {
							if (d.isJsonPrimitive()) j.depends.add(d.getAsString());
							else if (d.isJsonObject() && d.getAsJsonObject().has("id")) j.depends.add(str(d.getAsJsonObject(), "id"));
						}
					}
				}
			} else if ((s = text(zip, "META-INF/neoforge.mods.toml")) != null) {
				j.loader = "neoforge";
				j.modId = toml(s, "modId");
				j.name = toml(s, "displayName");
			} else if ((s = text(zip, "META-INF/mods.toml")) != null) {
				j.loader = "forge";
				j.modId = toml(s, "modId");
				j.name = toml(s, "displayName");
			}
		} catch (java.util.zip.ZipException e) {
			j.broken = true;
		} catch (Exception e) {
			// unreadable metadata: treat as unknown, not broken
		}
		if (j.version != null && j.version.contains("${")) j.version = null;
		j.depends.remove(j.modId);
		return j;
	}

	private static String joinSpec(JsonElement e) {
		if (e.isJsonArray()) {
			List<String> parts = new ArrayList<>();
			e.getAsJsonArray().forEach(x -> parts.add(x.getAsString()));
			return String.join(" ", parts);
		}
		return e.getAsString();
	}

	private static String text(ZipFile zip, String name) throws IOException {
		ZipEntry e = zip.getEntry(name);
		if (e == null) return null;
		try (InputStream in = zip.getInputStream(e)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	private static String toml(String s, String key) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(s);
		return m.find() ? m.group(1) : null;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	public static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sha1(Path p) throws IOException {
		return sha1(Files.readAllBytes(p));
	}

	/** CurseForge fingerprint: MurmurHash2 (seed 1) over the file without whitespace bytes. */
	public static long curseForgeFingerprint(byte[] in) {
		byte[] b = new byte[in.length];
		int n = 0;
		for (byte x : in) {
			if (x != 9 && x != 10 && x != 13 && x != 32) b[n++] = x;
		}
		final int m = 0x5bd1e995;
		int h = 1 ^ n;
		int i = 0;
		while (n - i >= 4) {
			int k = (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
			k *= m;
			k ^= k >>> 24;
			k *= m;
			h = h * m ^ k;
			i += 4;
		}
		switch (n - i) {
			case 3: h ^= (b[i + 2] & 0xff) << 16;
			case 2: h ^= (b[i + 1] & 0xff) << 8;
			case 1: h ^= b[i] & 0xff; h *= m;
			default: break;
		}
		h ^= h >>> 13;
		h *= m;
		h ^= h >>> 15;
		return h & 0xffffffffL;
	}
}
