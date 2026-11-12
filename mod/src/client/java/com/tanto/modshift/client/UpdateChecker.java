package com.tanto.modshift.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

/** Scans the mods folder and works out what to download, add or remove. Nothing is changed here. */
public final class UpdateChecker {
	public enum Kind { UPDATE, DEPENDENCY, ADD, REMOVE, MANUAL }

	/** One row in the ModShift list. */
	public static final class Entry {
		public final Kind kind;
		public String source;          // "mr" or "cf"
		public String projectId;       // Modrinth project id or CurseForge mod id
		public String title;
		public Path oldFile;           // jar that is replaced or removed
		public String oldVersion;
		public String newVersion;
		public String versionId;       // Modrinth version id or CurseForge file id
		public String url;
		public String fileName;
		public String sha1;
		public long size;
		public Instant oldDate = Instant.EPOCH;
		public Instant newDate = Instant.EPOCH;
		public String pageUrl;
		public String note;            // shown in the tooltip
		public boolean byName;         // matched by name only
		public boolean selected;

		Entry(Kind kind) {
			this.kind = kind;
			this.selected = kind == Kind.UPDATE || kind == Kind.DEPENDENCY || kind == Kind.ADD;
		}

		public boolean downloads() {
			return kind == Kind.UPDATE || kind == Kind.DEPENDENCY || kind == Kind.ADD;
		}
	}

	public static final class Suggestion {
		public String projectId, title, description, forName, iconUrl;
		public long downloads;
	}

	public static final class Result {
		public final List<Entry> entries = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
		public final List<String> unknown = new ArrayList<>();
		public final List<Suggestion> optional = new ArrayList<>();
		public final Set<String> installedMr = new HashSet<>();
		public final Set<String> installedSlugs = new HashSet<>();
		public final Map<String, Integer> categories = new HashMap<>();
		public final Map<String, String> titles = new HashMap<>();
		public int scanned;
		public int upToDate;
		public String mcVersion;

		public long countDownloads() {
			return entries.stream().filter(e -> e.downloads()).count();
		}
	}

	// ------------------------------------------------------------------

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	public static String mcVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("26.2");
	}

	static boolean quilt() {
		return FabricLoader.getInstance().isModLoaded("quilt_loader");
	}

	static List<String> loaders() {
		return quilt() ? List.of("quilt", "fabric") : List.of("fabric");
	}

	static List<String> versionTypes() {
		List<String> t = new ArrayList<>(List.of("release"));
		if (Config.includeBeta || Config.includeAlpha) t.add("beta");
		if (Config.includeAlpha) t.add("alpha");
		return t;
	}

	private static final Map<Integer, String> CF_TYPES = Map.of(1, "release", 2, "beta", 3, "alpha");

	// ------------------------------------------------------------------

	public static Result check() throws IOException, InterruptedException {
		Result res = new Result();
		res.mcVersion = mcVersion();
		boolean cf = Config.curseForgeEnabled();

		// 1. Read every jar.
		List<JarInfo> jars = new ArrayList<>();
		try (Stream<Path> s = Files.list(modsDir())) {
			for (Path p : s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()) {
				jars.add(JarInfo.read(p, cf));
			}
		}
		res.scanned = jars.size();

		// 2. Files that cannot work in this pack.
		List<JarInfo> usable = new ArrayList<>();
		for (JarInfo j : jars) {
			String problem = null;
			if (j.broken) problem = "broken";
			else if ("forge".equals(j.loader) || "neoforge".equals(j.loader)) problem = "wrongLoader";
			else if ("quilt".equals(j.loader) && !quilt()) problem = "wrongLoader";
			if (problem != null) {
				Entry e = new Entry(Kind.REMOVE);
				e.oldFile = j.path;
				e.title = j.name != null ? j.name : j.fileName();
				e.note = problem.equals("broken") ? "§broken" : "§wrongLoader:" + j.loader;
				res.entries.add(e);
			} else {
				usable.add(j);
			}
		}
		if (usable.isEmpty()) return res;

		Map<String, JarInfo> byHash = new LinkedHashMap<>();
		for (JarInfo j : usable) byHash.putIfAbsent(j.sha1, j);

		// 3. Modrinth: what is installed, and what is newest.
		JsonObject body = new JsonObject();
		body.add("hashes", Net.arr(new ArrayList<>(byHash.keySet())));
		body.addProperty("algorithm", "sha1");
		JsonObject current = Net.mrPost("/version_files", body).getAsJsonObject();
		body.add("loaders", Net.arr(loaders()));
		body.add("game_versions", Net.arr(List.of(res.mcVersion)));
		body.add("version_types", Net.arr(versionTypes()));
		JsonObject latest = Net.mrPost("/version_files/update", body).getAsJsonObject();

		Map<String, JsonObject> curByProject = new LinkedHashMap<>();   // Modrinth project -> current version
		Map<String, JarInfo> jarByProject = new HashMap<>();
		Map<String, JsonObject> newByProject = new HashMap<>();
		List<JarInfo> notOnModrinth = new ArrayList<>();
		for (JarInfo j : byHash.values()) {
			JsonElement c = current.get(j.sha1);
			if (c == null) { notOnModrinth.add(j); continue; }
			JsonObject cur = c.getAsJsonObject();
			String pid = Net.str(cur, "project_id");
			if (jarByProject.containsKey(pid)) { duplicate(res, jarByProject, curByProject, pid, j, cur); continue; }
			jarByProject.put(pid, j);
			curByProject.put(pid, cur);
			res.installedMr.add(pid);
			JsonElement u = latest.get(j.sha1);
			if (u != null) newByProject.put(pid, u.getAsJsonObject());
		}

		// Project info (titles, categories, library flag) for everything installed from Modrinth.
		Map<String, JsonObject> projects = projects(new ArrayList<>(curByProject.keySet()));
		for (JsonObject p : projects.values()) {
			res.installedSlugs.add(norm(Net.str(p, "slug")));
			res.titles.put(Net.str(p, "id"), Net.str(p, "title"));
			for (JsonElement c : p.getAsJsonArray("categories")) res.categories.merge(c.getAsString(), 1, Integer::sum);
		}

		for (Map.Entry<String, JsonObject> en : curByProject.entrySet()) {
			String pid = en.getKey();
			JsonObject cur = en.getValue();
			JsonObject upd = newByProject.get(pid);
			JarInfo j = jarByProject.get(pid);
			if (upd == null || Config.ignored.contains("mr:" + pid)) { res.upToDate++; continue; }
			JsonObject file = primaryFile(upd);
			boolean same = file == null || Net.str(upd, "id").equals(Net.str(cur, "id"))
					|| j.sha1.equalsIgnoreCase(Net.str(file.getAsJsonObject("hashes"), "sha1"));
			if (same || date(upd).isBefore(date(cur))) { res.upToDate++; continue; }
			Entry e = mrEntry(Kind.UPDATE, pid, upd, file);
			e.oldFile = j.path;
			e.oldVersion = Net.str(cur, "version_number");
			e.oldDate = date(cur);
			res.entries.add(e);
		}

		// 4. CurseForge for jars Modrinth does not know.
		Map<String, JsonObject> cfProjects = new HashMap<>();
		if (cf && !notOnModrinth.isEmpty()) {
			notOnModrinth = curseForge(res, notOnModrinth, cfProjects);
		}

		// 5. Last try: search Modrinth by the name inside the jar.
		for (JarInfo j : notOnModrinth) {
			if (Config.nameSearch && (j.modId != null || j.name != null)) {
				try {
					if (nameMatch(res, j)) continue;
				} catch (IOException ignored) {
				}
			}
			res.unknown.add(j.fileName());
		}

		// 6. Required dependencies of new versions that are missing.
		if (Config.addDependencies) addMissingDependencies(res, res.entries.stream().filter(Entry::downloads).toList());

		// 7. Conflicts between installed mods.
		conflicts(res, curByProject, newByProject, projects);

		// 8. Libraries nothing needs any more.
		orphans(res, usable, jarByProject, curByProject, newByProject, projects);

		// 9. Optional add-ons worth suggesting.
		optional(res, curByProject, newByProject, projects);

		// 10. Titles for everything from Modrinth.
		fillTitles(res);
		res.entries.sort(Comparator.comparingInt((Entry e) -> e.kind.ordinal()).thenComparing(e -> e.title == null ? "" : e.title.toLowerCase(Locale.ROOT)));
		return res;
	}

	// ------------------------------------------------------------------ Modrinth helpers

	static Entry mrEntry(Kind kind, String pid, JsonObject version, JsonObject file) {
		Entry e = new Entry(kind);
		e.source = "mr";
		e.projectId = pid;
		e.newVersion = Net.str(version, "version_number");
		e.versionId = Net.str(version, "id");
		e.newDate = date(version);
		e.url = Net.str(file, "url");
		e.fileName = Net.str(file, "filename");
		e.sha1 = Net.str(file.getAsJsonObject("hashes"), "sha1");
		e.size = file.has("size") ? file.get("size").getAsLong() : 0;
		e.pageUrl = "https://modrinth.com/mod/" + pid;
		return e;
	}

	/** Newest Modrinth version of a project for this Minecraft version and loader, or null. */
	static JsonObject bestVersion(String pid, String mc) throws IOException, InterruptedException {
		JsonElement r = Net.mrGet("/project/" + pid + "/version?include_changelog=false&loaders=" + Net.enc(Net.arr(loaders()).toString())
				+ "&game_versions=" + Net.enc(Net.arr(List.of(mc)).toString()));
		if (r == null || !r.isJsonArray()) return null;
		List<String> types = versionTypes();
		for (JsonElement e : r.getAsJsonArray()) {
			JsonObject v = e.getAsJsonObject();
			if (types.contains(Net.str(v, "version_type")) && primaryFile(v) != null) return v;
		}
		return null;
	}

	/** Adds a project the user picked (search or suggestion) to the result, with its dependencies. */
	public static Entry addProject(Result res, String pid, String title) throws IOException, InterruptedException {
		for (Entry e : res.entries) if (pid.equals(e.projectId) && e.downloads()) return e;
		if (res.installedMr.contains(pid)) return null;
		JsonObject v = bestVersion(pid, res.mcVersion);
		if (v == null) throw new IOException(title + ": no version for Minecraft " + res.mcVersion);
		Entry e = mrEntry(Kind.ADD, pid, v, primaryFile(v));
		e.title = title;
		res.entries.add(e);
		if (Config.addDependencies) {
			addMissingDependencies(res, List.of(e));
			fillTitles(res);
		}
		return e;
	}

	static Map<String, JsonObject> projects(List<String> ids) throws IOException, InterruptedException {
		Map<String, JsonObject> out = new HashMap<>();
		for (int i = 0; i < ids.size(); i += 100) {
			JsonElement r = Net.mrGet("/projects?ids=" + Net.enc(Net.arr(ids.subList(i, Math.min(ids.size(), i + 100))).toString()));
			if (r == null || !r.isJsonArray()) continue;
			for (JsonElement e : r.getAsJsonArray()) out.put(Net.str(e.getAsJsonObject(), "id"), e.getAsJsonObject());
		}
		return out;
	}

	private static void fillTitles(Result res) throws IOException, InterruptedException {
		res.entries.stream().filter(e -> e.title != null && e.projectId != null).forEach(e -> res.titles.putIfAbsent(e.projectId, e.title));
		List<String> need = res.entries.stream().filter(e -> "mr".equals(e.source) && e.title == null).map(e -> e.projectId).distinct().toList();
		if (need.isEmpty()) return;
		Map<String, JsonObject> p = projects(need);
		for (Entry e : res.entries) {
			if ("mr".equals(e.source) && e.title == null) {
				JsonObject o = p.get(e.projectId);
				e.title = o != null ? Net.str(o, "title") : e.fileName;
				res.titles.put(e.projectId, e.title);
				if (o != null) e.pageUrl = "https://modrinth.com/mod/" + Net.str(o, "slug");
			}
		}
	}

	private static void duplicate(Result res, Map<String, JarInfo> jarByProject, Map<String, JsonObject> curByProject,
			String pid, JarInfo j, JsonObject cur) {
		// Two jars of the same mod: keep the newer one, offer to remove the other.
		JsonObject kept = curByProject.get(pid);
		JarInfo older = j;
		if (date(cur).isAfter(date(kept))) {
			older = jarByProject.get(pid);
			jarByProject.put(pid, j);
			curByProject.put(pid, cur);
		}
		Entry e = new Entry(Kind.REMOVE);
		e.oldFile = older.path;
		e.title = older.name != null ? older.name : older.fileName();
		e.note = "§duplicate";
		e.selected = true; // Fabric refuses to start with two copies of a mod
		res.entries.add(e);
	}

	private static boolean nameMatch(Result res, JarInfo j) throws IOException, InterruptedException {
		String q = j.name != null ? j.name : j.modId;
		JsonArray facets = new JsonArray();
		facets.add(Net.arr(List.of("project_type:mod")));
		facets.add(Net.arr(loaders().stream().map(l -> "categories:" + l).toList()));
		JsonElement r = Net.mrGet("/search?limit=5&query=" + Net.enc(q) + "&facets=" + Net.enc(facets.toString()));
		if (r == null) return false;
		Set<String> ids = new HashSet<>();
		if (j.modId != null) ids.add(norm(j.modId));
		if (j.name != null) ids.add(norm(j.name));
		for (JsonElement h : r.getAsJsonObject().getAsJsonArray("hits")) {
			JsonObject hit = h.getAsJsonObject();
			if (!ids.contains(norm(Net.str(hit, "slug"))) && !ids.contains(norm(Net.str(hit, "title")))) continue;
			String pid = Net.str(hit, "project_id");
			if (res.installedMr.contains(pid) || Config.ignored.contains("mr:" + pid)) return false;
			res.installedMr.add(pid);
			JsonObject v = bestVersion(pid, res.mcVersion);
			if (v == null || j.sha1.equalsIgnoreCase(Net.str(primaryFile(v).getAsJsonObject("hashes"), "sha1"))) {
				res.upToDate++;
				return true;
			}
			if (j.version != null && j.version.equals(Net.str(v, "version_number"))) { res.upToDate++; return true; }
			Entry e = mrEntry(Kind.UPDATE, pid, v, primaryFile(v));
			e.title = Net.str(hit, "title");
			e.oldFile = j.path;
			e.oldVersion = j.version;
			e.byName = true;
			e.selected = false; // a name match can be wrong: the user decides
			e.note = "§byName";
			res.entries.add(e);
			return true;
		}
		return false;
	}

	private static void addMissingDependencies(Result res, List<Entry> start) throws IOException, InterruptedException {
		Set<String> have = new HashSet<>(res.installedMr);
		for (Entry e : res.entries) if (e.downloads() && "mr".equals(e.source)) have.add(e.projectId);
		List<Entry> queue = new ArrayList<>(start);
		for (int i = 0; i < queue.size() && i < 200; i++) {
			Entry parent = queue.get(i);
			if (!"mr".equals(parent.source)) continue;
			JsonElement v = Net.mrGet("/version/" + parent.versionId);
			if (v == null) continue;
			for (JsonElement d : v.getAsJsonObject().getAsJsonArray("dependencies")) {
				JsonObject dep = d.getAsJsonObject();
				if (!"required".equals(Net.str(dep, "dependency_type"))) continue;
				String pid = Net.str(dep, "project_id");
				if (pid == null || !have.add(pid)) continue;
				JsonObject best = bestVersion(pid, res.mcVersion);
				if (best == null) {
					res.warnings.add("§missingDep:" + pid + ":" + parent.projectId);
					continue;
				}
				Entry e = mrEntry(Kind.DEPENDENCY, pid, best, primaryFile(best));
				e.note = "§requiredBy:" + parent.projectId;
				res.entries.add(e);
				queue.add(e);
			}
		}
	}

	private static void conflicts(Result res, Map<String, JsonObject> cur, Map<String, JsonObject> upd, Map<String, JsonObject> projects) {
		Set<String> seen = new HashSet<>();
		for (String pid : cur.keySet()) {
			JsonObject v = upd.getOrDefault(pid, cur.get(pid));
			for (JsonElement d : v.getAsJsonArray("dependencies")) {
				JsonObject dep = d.getAsJsonObject();
				String other = Net.str(dep, "project_id");
				if (!"incompatible".equals(Net.str(dep, "dependency_type")) || other == null || !cur.containsKey(other)) continue;
				String key = pid.compareTo(other) < 0 ? pid + other : other + pid;
				if (seen.add(key)) res.warnings.add("§conflict:" + title(projects, pid) + ":" + title(projects, other));
			}
		}
	}

	private static void orphans(Result res, List<JarInfo> jars, Map<String, JarInfo> jarByProject, Map<String, JsonObject> cur,
			Map<String, JsonObject> upd, Map<String, JsonObject> projects) {
		Set<String> neededIds = new HashSet<>();
		for (JarInfo j : jars) j.depends.forEach(d -> neededIds.add(d.toLowerCase(Locale.ROOT)));
		Set<String> neededProjects = new HashSet<>();
		for (String pid : cur.keySet()) {
			for (JsonObject v : new JsonObject[] {cur.get(pid), upd.get(pid)}) {
				if (v == null) continue;
				for (JsonElement d : v.getAsJsonArray("dependencies")) {
					String other = Net.str(d.getAsJsonObject(), "project_id");
					if (other != null && !"incompatible".equals(Net.str(d.getAsJsonObject(), "dependency_type"))) neededProjects.add(other);
				}
			}
		}
		for (Map.Entry<String, JarInfo> en : jarByProject.entrySet()) {
			JsonObject p = projects.get(en.getKey());
			if (p == null || !isLibrary(p) || neededProjects.contains(en.getKey())) continue;
			JarInfo j = en.getValue();
			List<String> ids = new ArrayList<>(j.provides);
			if (j.modId != null) ids.add(j.modId);
			if (ids.isEmpty() || ids.stream().anyMatch(i -> neededIds.contains(i.toLowerCase(Locale.ROOT)))) continue;
			// Fabric API is split into many modules; anything depending on one of them needs it.
			if ("fabric-api".equals(j.modId) && neededIds.stream().anyMatch(i -> i.startsWith("fabric-") || i.equals("fabric"))) continue;
			Entry e = new Entry(Kind.REMOVE);
			e.source = "mr";
			e.projectId = en.getKey();
			e.oldFile = j.path;
			e.title = Net.str(p, "title");
			e.note = "§orphan";
			e.selected = false;
			res.entries.add(e);
		}
	}

	private static boolean isLibrary(JsonObject p) {
		for (String k : new String[] {"categories", "additional_categories"}) {
			JsonArray a = p.getAsJsonArray(k);
			if (a != null) for (JsonElement c : a) if ("library".equals(c.getAsString())) return true;
		}
		return false;
	}

	private static void optional(Result res, Map<String, JsonObject> cur, Map<String, JsonObject> upd, Map<String, JsonObject> projects)
			throws IOException, InterruptedException {
		Map<String, String> want = new LinkedHashMap<>();
		for (String pid : cur.keySet()) {
			JsonObject v = upd.getOrDefault(pid, cur.get(pid));
			for (JsonElement d : v.getAsJsonArray("dependencies")) {
				JsonObject dep = d.getAsJsonObject();
				String other = Net.str(dep, "project_id");
				if ("optional".equals(Net.str(dep, "dependency_type")) && other != null && !cur.containsKey(other)
						&& !Config.dismissed.contains(other)) {
					want.putIfAbsent(other, title(projects, pid));
				}
			}
		}
		if (want.isEmpty()) return;
		Map<String, JsonObject> info = projects(new ArrayList<>(want.keySet()));
		for (Map.Entry<String, String> w : want.entrySet()) {
			JsonObject p = info.get(w.getKey());
			if (p == null || !supports(p, res.mcVersion)) continue;
			Suggestion s = new Suggestion();
			s.projectId = w.getKey();
			s.title = Net.str(p, "title");
			s.description = Net.str(p, "description");
			s.downloads = p.has("downloads") ? p.get("downloads").getAsLong() : 0;
			s.forName = w.getValue();
			res.optional.add(s);
			if (res.optional.size() >= 20) break;
		}
	}

	private static boolean supports(JsonObject p, String mc) {
		JsonArray gv = p.getAsJsonArray("game_versions");
		JsonArray ld = p.getAsJsonArray("loaders");
		boolean v = gv == null || gv.asList().stream().anyMatch(x -> mc.equals(x.getAsString()));
		boolean l = ld == null || ld.asList().stream().anyMatch(x -> loaders().contains(x.getAsString()));
		return v && l;
	}

	/** Popular mods in the same categories as the pack, for the suggestions screen. */
	public static List<Suggestion> popular(Result res, int offset) throws IOException, InterruptedException {
		List<String> top = res.categories.entrySet().stream()
				.filter(e -> !e.getKey().equals("library") && !loaders().contains(e.getKey()))
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(4).map(Map.Entry::getKey).toList();
		JsonArray facets = new JsonArray();
		facets.add(Net.arr(List.of("project_type:mod")));
		facets.add(Net.arr(loaders().stream().map(l -> "categories:" + l).toList()));
		facets.add(Net.arr(List.of("versions:" + res.mcVersion)));
		if (!top.isEmpty()) facets.add(Net.arr(top.stream().map(c -> "categories:" + c).toList()));
		JsonElement r = Net.mrGet("/search?limit=20&index=downloads&offset=" + offset + "&facets=" + Net.enc(facets.toString()));
		List<Suggestion> out = new ArrayList<>();
		if (r == null) return out;
		for (JsonElement h : r.getAsJsonObject().getAsJsonArray("hits")) {
			JsonObject hit = h.getAsJsonObject();
			String pid = Net.str(hit, "project_id");
			if (res.installedMr.contains(pid) || res.installedSlugs.contains(norm(Net.str(hit, "slug"))) || Config.dismissed.contains(pid)) continue;
			if (res.entries.stream().anyMatch(e -> pid.equals(e.projectId))) continue;
			Suggestion s = new Suggestion();
			s.projectId = pid;
			s.title = Net.str(hit, "title");
			s.description = Net.str(hit, "description");
			s.downloads = hit.has("downloads") ? hit.get("downloads").getAsLong() : 0;
			out.add(s);
		}
		return out;
	}

	/** Free text search on Modrinth for the "add mods" screen. */
	public static List<Suggestion> search(Result res, String query) throws IOException, InterruptedException {
		JsonArray facets = new JsonArray();
		facets.add(Net.arr(List.of("project_type:mod")));
		facets.add(Net.arr(loaders().stream().map(l -> "categories:" + l).toList()));
		facets.add(Net.arr(List.of("versions:" + res.mcVersion)));
		JsonElement r = Net.mrGet("/search?limit=20&index=relevance&query=" + Net.enc(query) + "&facets=" + Net.enc(facets.toString()));
		List<Suggestion> out = new ArrayList<>();
		if (r == null) return out;
		for (JsonElement h : r.getAsJsonObject().getAsJsonArray("hits")) {
			JsonObject hit = h.getAsJsonObject();
			Suggestion s = new Suggestion();
			s.projectId = Net.str(hit, "project_id");
			s.title = Net.str(hit, "title");
			s.description = Net.str(hit, "description");
			s.downloads = hit.has("downloads") ? hit.get("downloads").getAsLong() : 0;
			out.add(s);
		}
		return out;
	}

	// ------------------------------------------------------------------ CurseForge

	private static List<JarInfo> curseForge(Result res, List<JarInfo> jars, Map<String, JsonObject> cfProjects)
			throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.add("fingerprints", Net.arr(jars.stream().map(j -> j.murmur).toList()));
		JsonElement r;
		try {
			r = Net.cfPost("/fingerprints/432", body);
		} catch (IOException e) {
			res.warnings.add("§cfError:" + e.getMessage());
			return jars;
		}
		Map<Long, JsonObject> matches = new HashMap<>();
		if (r != null) {
			for (JsonElement m : r.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("exactMatches")) {
				JsonObject file = m.getAsJsonObject().getAsJsonObject("file");
				matches.put(file.get("fileFingerprint").getAsLong(), m.getAsJsonObject());
			}
		}
		List<JarInfo> left = new ArrayList<>();
		Map<Integer, JarInfo> jarByMod = new LinkedHashMap<>();
		Map<Integer, JsonObject> curByMod = new HashMap<>();
		for (JarInfo j : jars) {
			JsonObject m = matches.get(j.murmur);
			if (m == null) { left.add(j); continue; }
			int modId = m.get("id").getAsInt();
			jarByMod.put(modId, j);
			curByMod.put(modId, m.getAsJsonObject("file"));
		}
		if (jarByMod.isEmpty()) return left;

		JsonObject ids = new JsonObject();
		ids.add("modIds", Net.arr(new ArrayList<>(jarByMod.keySet())));
		JsonElement mods = Net.cfPost("/mods", ids);
		if (mods != null) for (JsonElement m : mods.getAsJsonObject().getAsJsonArray("data")) {
			cfProjects.put(Net.str(m.getAsJsonObject(), "id"), m.getAsJsonObject());
		}

		int loaderType = quilt() ? 5 : 4;
		List<String> types = versionTypes();
		for (Map.Entry<Integer, JarInfo> en : jarByMod.entrySet()) {
			int modId = en.getKey();
			if (Config.ignored.contains("cf:" + modId)) { res.upToDate++; continue; }
			JsonObject cur = curByMod.get(modId);
			JsonObject proj = cfProjects.get(String.valueOf(modId));
			JsonElement files = Net.cfGet("/mods/" + modId + "/files?pageSize=50&gameVersion=" + Net.enc(res.mcVersion) + "&modLoaderType=" + loaderType);
			JsonObject best = null;
			if (files != null) {
				List<JsonObject> list = new ArrayList<>();
				for (JsonElement f : files.getAsJsonObject().getAsJsonArray("data")) {
					JsonObject fo = f.getAsJsonObject();
					boolean mcOk = fo.getAsJsonArray("gameVersions").asList().stream().anyMatch(x -> res.mcVersion.equals(x.getAsString()));
					if (mcOk && types.contains(CF_TYPES.getOrDefault(fo.get("releaseType").getAsInt(), "release"))) list.add(fo);
				}
				list.sort(Comparator.comparing(UpdateChecker::cfDate).reversed());
				if (!list.isEmpty()) best = list.get(0);
			}
			if (best == null || best.get("id").getAsLong() == cur.get("id").getAsLong() || cfDate(best).isBefore(cfDate(cur))) {
				res.upToDate++;
				continue;
			}
			String page = proj != null && proj.has("links") ? Net.str(proj.getAsJsonObject("links"), "websiteUrl") : null;
			boolean allowed = proj == null || !proj.has("allowModDistribution") || proj.get("allowModDistribution").isJsonNull()
					|| proj.get("allowModDistribution").getAsBoolean();
			String url = Net.str(best, "downloadUrl");
			Entry e = new Entry(allowed && url != null ? Kind.UPDATE : Kind.MANUAL);
			e.source = "cf";
			e.projectId = String.valueOf(modId);
			e.title = proj != null ? Net.str(proj, "name") : en.getValue().fileName();
			e.oldFile = en.getValue().path;
			e.oldVersion = Net.str(cur, "displayName");
			e.oldDate = cfDate(cur);
			e.newVersion = Net.str(best, "displayName");
			e.newDate = cfDate(best);
			e.versionId = Net.str(best, "id");
			e.url = url;
			e.fileName = Net.str(best, "fileName");
			e.size = best.has("fileLength") ? best.get("fileLength").getAsLong() : 0;
			for (JsonElement h : best.getAsJsonArray("hashes")) {
				if (h.getAsJsonObject().get("algo").getAsInt() == 1) e.sha1 = Net.str(h.getAsJsonObject(), "value");
			}
			e.pageUrl = page == null ? null : page.replaceAll("/$", "") + "/files/" + e.versionId;
			if (e.kind == Kind.MANUAL) e.note = "§manual";
			if (e.sha1 == null && e.kind == Kind.UPDATE) { res.upToDate++; continue; }
			res.entries.add(e);
		}
		return left;
	}

	private static Instant cfDate(JsonObject f) {
		try {
			return Instant.parse(Net.str(f, "fileDate"));
		} catch (Exception e) {
			return Instant.EPOCH;
		}
	}

	// ------------------------------------------------------------------ misc

	static JsonObject primaryFile(JsonObject version) {
		JsonArray files = version.getAsJsonArray("files");
		if (files == null || files.isEmpty()) return null;
		for (JsonElement f : files) {
			JsonObject o = f.getAsJsonObject();
			if (o.has("primary") && o.get("primary").getAsBoolean()) return o;
		}
		return files.get(0).getAsJsonObject();
	}

	static Instant date(JsonObject v) {
		try {
			return Instant.parse(Net.str(v, "date_published"));
		} catch (Exception e) {
			return Instant.EPOCH;
		}
	}

	private static String title(Map<String, JsonObject> projects, String pid) {
		JsonObject p = projects.get(pid);
		return p != null ? Net.str(p, "title") : pid;
	}

	static String norm(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/** Changelogs between the installed version and the new one (newest first, at most 10). */
	public static List<String[]> changelogs(Entry e) throws IOException, InterruptedException {
		List<String[]> out = new ArrayList<>();
		if ("cf".equals(e.source)) {
			JsonElement r = Net.cfGet("/mods/" + e.projectId + "/files/" + e.versionId + "/changelog");
			String html = r == null ? "" : Net.str(r.getAsJsonObject(), "data");
			out.add(new String[] {e.newVersion, htmlToText(html == null ? "" : html)});
			return out;
		}
		JsonElement r = Net.mrGet("/project/" + e.projectId + "/version?include_changelog=true&loaders=" + Net.enc(Net.arr(loaders()).toString())
				+ "&game_versions=" + Net.enc(Net.arr(List.of(mcVersion())).toString()));
		if (r != null && r.isJsonArray()) {
			for (JsonElement el : r.getAsJsonArray()) {
				JsonObject v = el.getAsJsonObject();
				Instant d = date(v);
				if (d.isAfter(e.newDate) || (e.oldFile != null && !d.isAfter(e.oldDate))) continue;
				out.add(new String[] {Net.str(v, "version_number"), cleanMarkdown(Net.str(v, "changelog"))});
				if (out.size() >= 10) break;
			}
		}
		if (out.isEmpty()) {
			JsonElement v = Net.mrGet("/version/" + e.versionId);
			if (v != null) out.add(new String[] {e.newVersion, cleanMarkdown(Net.str(v.getAsJsonObject(), "changelog"))});
		}
		return out;
	}

	static String cleanMarkdown(String s) {
		if (s == null) return "";
		return s.replace("\r", "")
				.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
				.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
				.replaceAll("(?m)^#{1,6}\\s*", "")
				.replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
				.replaceAll("\\*\\*|__|`", "")
				.replaceAll("<[^>]+>", "")
				.replaceAll("\n{3,}", "\n\n").trim();
	}

	static String htmlToText(String h) {
		String s = h.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h\\d>|</div>", "\n").replaceAll("(?i)<li[^>]*>", "• ").replaceAll("<[^>]+>", "");
		s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
		return s.replaceAll("\n{3,}", "\n\n").trim();
	}

	private UpdateChecker() {}
}
