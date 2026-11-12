package com.tanto.modshift.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** HTTP helpers for Modrinth, CurseForge and the translator. */
public final class Net {
	static final String MR = "https://api.modrinth.com/v2";
	static final String CF = "https://api.curseforge.com/v1";
	static final String USER_AGENT = "tanto81pro/ModShift/" + ModShiftClient.version() + " (github.com/tanto81pro/Mod-Shift)";
	static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	// ---------- Modrinth ----------
	static JsonElement mrGet(String path) throws IOException, InterruptedException {
		return send(base(MR + path).GET().build(), true);
	}

	static JsonElement mrPost(String path, JsonObject body) throws IOException, InterruptedException {
		JsonElement r = send(base(MR + path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), true);
		return r == null ? new JsonObject() : r;
	}

	// ---------- CurseForge ----------
	static String cfBase() {
		String proxy = Config.cfProxy.trim().replaceAll("/+$", "");
		return proxy.isEmpty() ? CF : proxy + "/v1";
	}

	static HttpRequest.Builder cfRequest(String path) {
		HttpRequest.Builder b = base(cfBase() + path).header("Accept", "application/json");
		if (Config.cfProxy.isBlank()) b.header("x-api-key", Config.cfKey.trim());
		return b;
	}

	static JsonElement cfGet(String path) throws IOException, InterruptedException {
		return send(cfRequest(path).GET().build(), false);
	}

	static JsonElement cfPost(String path, JsonObject body) throws IOException, InterruptedException {
		return send(cfRequest(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), false);
	}

	/** Download request for a file; CurseForge downloads need the key (or go through the proxy). */
	static HttpRequest downloadRequest(String url) {
		String proxy = Config.cfProxy.trim().replaceAll("/+$", "");
		boolean forge = url.contains(".forgecdn.net/");
		if (forge && !proxy.isEmpty()) {
			return base(proxy + "/dl?u=" + enc(url)).timeout(Duration.ofMinutes(5)).GET().build();
		}
		HttpRequest.Builder b = base(url).timeout(Duration.ofMinutes(5));
		if (forge && !Config.cfKey.isBlank()) b.header("x-api-key", Config.cfKey.trim());
		return b.GET().build();
	}

	static boolean trustedDownload(String url) {
		return url.startsWith("https://cdn.modrinth.com/") || url.startsWith("https://edge.forgecdn.net/")
				|| url.startsWith("https://mediafilez.forgecdn.net/");
	}

	// ---------- Translation ----------
	static String translate(String text, String target) throws IOException, InterruptedException {
		StringBuilder out = new StringBuilder();
		StringBuilder chunk = new StringBuilder();
		for (String line : text.split("\n", -1)) {
			if (chunk.length() + line.length() > 1500) {
				out.append(translateChunk(chunk.toString(), target));
				chunk.setLength(0);
			}
			chunk.append(line).append('\n');
		}
		if (!chunk.isEmpty()) out.append(translateChunk(chunk.toString(), target));
		return out.toString().trim();
	}

	private static String translateChunk(String text, String target) throws IOException, InterruptedException {
		String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + target + "&dt=t&q=" + enc(text);
		HttpResponse<String> r = HTTP.send(base(url).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException("translator answered " + r.statusCode());
		StringBuilder sb = new StringBuilder();
		for (JsonElement part : JsonParser.parseString(r.body()).getAsJsonArray().get(0).getAsJsonArray()) {
			sb.append(part.getAsJsonArray().get(0).getAsString());
		}
		return sb.toString();
	}

	// ---------- shared ----------
	static HttpRequest.Builder base(String url) {
		return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(30));
	}

	private static JsonElement send(HttpRequest req, boolean modrinth) throws IOException, InterruptedException {
		for (int attempt = 0; attempt < 3; attempt++) {
			HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 429) {
				long wait = r.headers().firstValueAsLong("X-Ratelimit-Reset").orElse(5);
				Thread.sleep(Math.min(60, Math.max(1, wait)) * 1000);
				continue;
			}
			if (r.statusCode() == 404) return null;
			if (!modrinth && (r.statusCode() == 401 || r.statusCode() == 403)) {
				throw new IOException("CurseForge rejected the API key (" + r.statusCode() + ")");
			}
			if (r.statusCode() / 100 != 2) throw new IOException((modrinth ? "Modrinth" : "CurseForge") + " answered " + r.statusCode());
			return JsonParser.parseString(r.body());
		}
		throw new IOException("Rate limited, try again in a minute");
	}

	static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	static JsonArray arr(List<?> l) {
		JsonArray a = new JsonArray();
		for (Object o : l) {
			if (o instanceof Number n) a.add(n);
			else a.add(String.valueOf(o));
		}
		return a;
	}

	static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private Net() {}
}
