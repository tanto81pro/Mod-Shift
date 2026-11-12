package com.tanto.modshift.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/** Finds updates for the jars in the mods folder using the Modrinth API. */
public final class UpdateChecker {
	static final String API = "https://api.modrinth.com/v2";
	static final String USER_AGENT = "tanto81pro/ModShift/" + ModShiftClient.version() + " (github.com/tanto81pro/Mod-Shift)";
	static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** One file that will be downloaded. */
	public static final class Update {
		public final String projectId;
		public String title;
		public final Path oldFile;          // null for a new dependency
		public final String oldVersion;     // may be null
		public final String newVersion;
		public final String url;
		public final String fileName;
		public final String sha1;
		public final long size;
		public String requiredBy;           // set for dependencies
		public boolean selected = true;

		Update(String projectId, Path oldFile, String oldVersion, String newVersion, String url, String fileName, String sha1, long size) {
			this.projectId = projectId;
			this.oldFile = oldFile;
			this.oldVersion = oldVersion;
			this.newVersion = newVersion;
			this.url = url;
			this.fileName = fileName;
			this.sha1 = sha1;
			this.size = size;
		}

		public boolean isDependency() {
			return oldFile == null;
		}
	}

	public static final class Result {
		public final List<Update> updates = new ArrayList<>();
		public int scanned;
		public int upToDate;
		public final List<String> unknown = new ArrayList<>();
		public String mcVersion;
	}

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	public static String mcVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("26.2");
	}

	static List<String> loaders() {
		return FabricLoader.getInstance().isModLoaded("quilt_loader") ? List.of("quilt", "fabric") : List.of("fabric");
	}

	static List<String> versionTypes() {
		List<String> t = new ArrayList<>(List.of("release"));
		if (Config.includeBeta || Config.includeAlpha) t.add("beta");
		if (Config.includeAlpha) t.add("alpha");
		return t;
	}

	public static Result check() throws IOException, InterruptedException {
		Result res = new Result();
		res.mcVersion = mcVersion();

		// 1. Hash every jar in the mods folder.
		Map<String, Path> byHash = new LinkedHashMap<>();
		try (Stream<Path> s = Files.list(modsDir())) {
			for (Path p : s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".jar")).sorted().toList()) {
				byHash.putIfAbsent(sha1(p), p);
			}
		}
		res.scanned = byHash.size();
		if (byHash.isEmpty()) return res;

		JsonArray hashes = new JsonArray();
		byHash.keySet().forEach(hashes::add);

		// 2. What is installed right now?
		JsonObject body = new JsonObject();
		body.add("hashes", hashes);
		body.addProperty("algorithm", "sha1");
		JsonObject current = post("/version_files", body).getAsJsonObject();

		Set<String> installedProjects = new LinkedHashSet<>();
		for (Map.Entry<String, Path> e : byHash.entrySet()) {
			JsonElement v = current.get(e.getKey());
			if (v == null) res.unknown.add(e.getValue().getFileName().toString());
			else installedProjects.add(v.getAsJsonObject().get("project_id").getAsString());
		}

		// 3. Newest version for this Minecraft version and loader.
		body.add("loaders", toArray(loaders()));
		body.add("game_versions", toArray(List.of(res.mcVersion)));
		body.add("version_types", toArray(versionTypes()));
		JsonObject latest = post("/version_files/update", body).getAsJsonObject();

		List<JsonObject> newVersions = new ArrayList<>();
		for (Map.Entry<String, Path> e : byHash.entrySet()) {
			JsonElement cur = current.get(e.getKey());
			JsonElement upd = latest.get(e.getKey());
			if (cur == null) continue;
			if (upd == null) { res.upToDate++; continue; }
			JsonObject c = cur.getAsJsonObject();
			JsonObject u = upd.getAsJsonObject();
			JsonObject file = primaryFile(u);
			if (file == null) { res.upToDate++; continue; }
			String newSha = file.getAsJsonObject("hashes").get("sha1").getAsString();
			boolean same = u.get("id").getAsString().equals(c.get("id").getAsString()) || newSha.equalsIgnoreCase(e.getKey());
			boolean older = date(u).isBefore(date(c)); // never "update" a newer beta to an older release
			if (same || older) { res.upToDate++; continue; }
			res.updates.add(new Update(u.get("project_id").getAsString(), e.getValue(), str(c, "version_number"),
					str(u, "version_number"), file.get("url").getAsString(), file.get("filename").getAsString(), newSha,
					file.has("size") ? file.get("size").getAsLong() : 0));
			newVersions.add(u);
		}

		// 4. Required dependencies that are not installed yet.
		if (Config.addDependencies) {
			Set<String> seen = new LinkedHashSet<>(installedProjects);
			for (int i = 0; i < newVersions.size(); i++) {
				JsonObject v = newVersions.get(i);
				for (JsonElement d : v.getAsJsonArray("dependencies")) {
					JsonObject dep = d.getAsJsonObject();
					if (!"required".equals(str(dep, "dependency_type"))) continue;
					String pid = str(dep, "project_id");
					if (pid == null || !seen.add(pid)) continue;
					JsonObject best = bestVersion(pid, res.mcVersion);
					if (best == null) continue;
					JsonObject file = primaryFile(best);
					if (file == null) continue;
					Update u = new Update(pid, null, null, str(best, "version_number"), file.get("url").getAsString(),
							file.get("filename").getAsString(), file.getAsJsonObject("hashes").get("sha1").getAsString(),
							file.has("size") ? file.get("size").getAsLong() : 0);
					u.requiredBy = str(v, "project_id"); // turned into a title in step 5
					res.updates.add(u);
					newVersions.add(best); // its dependencies are checked too
				}
			}
		}

		// 5. Human readable names.
		if (!res.updates.isEmpty()) {
			Map<String, String> titles = titles(res.updates.stream().map(u -> u.projectId).distinct().toList());
			for (Update u : res.updates) {
				u.title = titles.getOrDefault(u.projectId, u.fileName);
				if (u.requiredBy != null) u.requiredBy = titles.getOrDefault(u.requiredBy, u.requiredBy);
			}
		}
		return res;
	}

	private static JsonObject bestVersion(String projectId, String mc) throws IOException, InterruptedException {
		String q = "/project/" + projectId + "/version?include_changelog=false&loaders=" + enc(toArray(loaders()).toString())
				+ "&game_versions=" + enc(toArray(List.of(mc)).toString());
		JsonElement r = get(q);
		if (r == null || !r.isJsonArray()) return null;
		List<String> types = versionTypes();
		for (JsonElement e : r.getAsJsonArray()) {
			JsonObject v = e.getAsJsonObject();
			if (types.contains(str(v, "version_type"))) return v;
		}
		return null;
	}

	private static Map<String, String> titles(List<String> ids) throws IOException, InterruptedException {
		Map<String, String> out = new HashMap<>();
		for (int i = 0; i < ids.size(); i += 100) {
			JsonArray chunk = toArray(ids.subList(i, Math.min(ids.size(), i + 100)));
			JsonElement r = get("/projects?ids=" + enc(chunk.toString()));
			if (r == null || !r.isJsonArray()) continue;
			for (JsonElement e : r.getAsJsonArray()) {
				JsonObject p = e.getAsJsonObject();
				out.put(str(p, "id"), str(p, "title"));
			}
		}
		return out;
	}

	static JsonObject primaryFile(JsonObject version) {
		JsonArray files = version.getAsJsonArray("files");
		if (files == null || files.isEmpty()) return null;
		for (JsonElement f : files) {
			if (f.getAsJsonObject().has("primary") && f.getAsJsonObject().get("primary").getAsBoolean()) return f.getAsJsonObject();
		}
		return files.get(0).getAsJsonObject();
	}

	private static Instant date(JsonObject v) {
		try {
			return Instant.parse(str(v, "date_published"));
		} catch (Exception e) {
			return Instant.EPOCH;
		}
	}

	static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static JsonArray toArray(List<String> l) {
		JsonArray a = new JsonArray();
		l.forEach(a::add);
		return a;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public static String sha1(Path p) throws IOException {
		try (InputStream in = Files.newInputStream(p)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static JsonElement get(String path) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(API + path)).header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(30)).GET().build();
		return send(req);
	}

	private static JsonElement post(String path, JsonObject body) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(API + path)).header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		JsonElement r = send(req);
		return r == null ? new JsonObject() : r;
	}

	private static JsonElement send(HttpRequest req) throws IOException, InterruptedException {
		for (int attempt = 0; attempt < 3; attempt++) {
			HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 429) {
				long wait = r.headers().firstValueAsLong("X-Ratelimit-Reset").orElse(5);
				Thread.sleep(Math.min(60, Math.max(1, wait)) * 1000);
				continue;
			}
			if (r.statusCode() == 404) return null;
			if (r.statusCode() / 100 != 2) throw new IOException("Modrinth answered " + r.statusCode());
			return JsonParser.parseString(r.body());
		}
		throw new IOException("Modrinth rate limit, try again in a minute");
	}

	private UpdateChecker() {}
}
