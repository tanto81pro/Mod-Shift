package com.tanto.modshift.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import com.tanto.modshift.client.UpdateChecker.Entry;

/** Changelog of one update, with a translate button. */
class DetailsScreen extends BaseScreen {
	private final Entry entry;
	private List<String[]> original;
	private List<String[]> translated;
	private boolean showTranslated;
	private volatile boolean loading;
	private volatile String error;

	DetailsScreen(ModShiftScreen parent, Entry entry) {
		super(Component.literal(entry.title == null ? "?" : entry.title), parent);
		this.entry = entry;
	}

	@Override
	protected void init() {
		String versions = (entry.oldVersion != null ? entry.oldVersion + "  →  " : "") + (entry.newVersion == null ? "" : entry.newVersion);
		header(Component.literal(versions + "   (" + ("cf".equals(entry.source) ? "CurseForge" : "Modrinth") + ")"));
		int cx = this.width / 2;
		int by = this.height - 28;

		Button tr = button(Component.translatable(showTranslated ? "modshift.showOriginal" : "modshift.translate"), cx - 206, by, 100, b -> translate());
		tr.active = original != null && !loading && !original.isEmpty();
		Button page = button(Component.translatable("modshift.openPage"), cx - 102, by, 100, b -> openLink(entry.pageUrl));
		page.active = entry.pageUrl != null;
		button(Component.translatable("modshift.ignore"), cx + 2, by, 100, b -> ignore())
				.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("modshift.ignore.tip")));
		button(Component.translatable("gui.back"), cx + 106, by, 100, b -> onClose());

		if (entry.note != null) addCentered(ModShiftScreen.noteText(entry.note, ModShiftClient.lastResult).copy().withStyle(ChatFormatting.GOLD), 34);
		if (error != null) {
			addMessage(Component.translatable("modshift.error", error).withStyle(ChatFormatting.RED), 50);
			return;
		}
		if (original == null) {
			addCentered(Component.translatable("modshift.loading"), this.height / 2 - 10);
			if (!loading) load();
			return;
		}
		buildText();
	}

	private void buildText() {
		clearList();
		int left = listLeft();
		int w = listWidth();
		List<Component> lines = new ArrayList<>();
		List<String[]> src = showTranslated && translated != null ? translated : original;
		if (src.isEmpty()) lines.add(Component.translatable("modshift.noChangelog").withStyle(ChatFormatting.GRAY));
		for (String[] v : src) {
			lines.add(Component.literal(v[0]).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			String body = v[1] == null || v[1].isBlank() ? Component.translatable("modshift.noChangelog").getString() : v[1];
			for (String l : wrap(body, w)) lines.add(Component.literal(l));
			lines.add(Component.empty());
		}
		int top = 48;
		int bottom = this.height - 56;
		int per = Math.max(3, (bottom - top) / 11);
		int pages = Math.max(1, (lines.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		int y = top;
		for (Component c : lines.subList(page * per, Math.min(lines.size(), (page + 1) * per))) {
			var s = new net.minecraft.client.gui.components.StringWidget(c, this.font).setMaxWidth(w);
			s.setX(left);
			s.setY(y);
			listWidget(s);
			y += 11;
		}
		pager(pages, bottom + 4, this::buildText);
	}

	private void load() {
		loading = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return UpdateChecker.changelogs(entry);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((list, ex) -> this.minecraft.execute(() -> {
			loading = false;
			if (ex != null) error = err(ex);
			else original = list;
			rebuildWidgets();
		}));
	}

	private void translate() {
		if (translated != null) {
			showTranslated = !showTranslated;
			page = 0;
			rebuildWidgets();
			return;
		}
		String lang = this.minecraft.options.languageCode;
		String target = lang == null ? "en" : lang.toLowerCase(Locale.ROOT).split("_")[0];
		if (target.equals("zh")) target = lang.toLowerCase(Locale.ROOT).contains("tw") ? "zh-TW" : "zh-CN";
		String tl = target;
		loading = true;
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				List<String[]> out = new ArrayList<>();
				for (String[] v : original) out.add(new String[] {v[0], v[1] == null || v[1].isBlank() ? v[1] : Net.translate(v[1], tl)});
				return out;
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((list, ex) -> this.minecraft.execute(() -> {
			loading = false;
			if (ex != null) error = Component.translatable("modshift.translateFailed").getString() + " " + err(ex);
			else { translated = list; showTranslated = true; page = 0; }
			rebuildWidgets();
		}));
	}

	private void ignore() {
		Config.ignored.add(entry.source + ":" + entry.projectId);
		Config.save();
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r != null) r.entries.remove(entry);
		onClose();
	}
}
