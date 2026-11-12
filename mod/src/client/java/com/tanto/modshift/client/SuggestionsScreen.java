package com.tanto.modshift.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.tanto.modshift.client.UpdateChecker.Suggestion;

/** Optional add-ons for installed mods, plus popular mods similar to the pack. Shared by the search screen. */
class SuggestionsScreen extends BaseScreen {
	protected final List<Suggestion> items = new ArrayList<>();
	protected volatile boolean loading;
	protected volatile String error;
	private int popularOffset;
	private boolean loaded;

	SuggestionsScreen(ModShiftScreen parent) {
		this(Component.translatable("modshift.suggest.title"), parent);
	}

	protected SuggestionsScreen(Component title, ModShiftScreen parent) {
		super(title, parent);
	}

	@Override
	protected void init() {
		header(Component.translatable("modshift.suggest.sub"));
		int cx = this.width / 2;
		Button refresh = button(Component.translatable("modshift.suggest.refresh"), cx - 104, this.height - 28, 100, b -> { popularOffset += 20; loadPopular(); });
		refresh.active = !loading;
		button(Component.translatable("gui.back"), cx + 4, this.height - 28, 100, b -> onClose());
		if (!loaded) {
			loaded = true;
			loadPopular();
		}
		buildList();
	}

	private void loadPopular() {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		loading = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				List<Suggestion> pop = UpdateChecker.popular(r, popularOffset);
				if (pop.isEmpty() && popularOffset > 0) {
					popularOffset = 0; // reached the end: start over
					pop = UpdateChecker.popular(r, 0);
				}
				return pop;
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((pop, ex) -> this.minecraft.execute(() -> {
			loading = false;
			items.clear();
			r.optional.stream().filter(s -> !Config.dismissed.contains(s.projectId) && !added(s)).forEach(items::add);
			if (ex != null) error = err(ex);
			else pop.stream().filter(s -> !added(s)).forEach(items::add);
			page = 0;
			rebuildWidgets();
		}));
	}

	protected boolean added(Suggestion s) {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		return r != null && (r.installedMr.contains(s.projectId) || r.entries.stream().anyMatch(e -> s.projectId.equals(e.projectId)));
	}

	protected boolean showDismiss() {
		return true;
	}

	protected int listTop() {
		return 36;
	}

	protected void buildList() {
		clearList();
		int top = listTop();
		if (loading) {
			listWidget(text(Component.translatable("modshift.loading"), top + 10));
			return;
		}
		if (error != null) listWidget(text(Component.translatable("modshift.error", error).withStyle(ChatFormatting.RED), top));
		int bottom = this.height - 58;
		int per = rowsBetween(top, bottom);
		int pages = Math.max(1, (items.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		int left = listLeft();
		int w = listWidth();
		if (items.isEmpty() && error == null) listWidget(text(Component.translatable("modshift.suggest.none").withStyle(ChatFormatting.GRAY), top + 10));
		int y = top;
		for (Suggestion s : items.subList(page * per, Math.min(items.size(), (page + 1) * per))) {
			MutableComponent label = Component.literal(s.title + "  ")
					.append(Component.literal("⬇ " + fmtNum(s.downloads)).withStyle(ChatFormatting.GRAY));
			if (s.forName != null) label.append(Component.literal("  · ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.translatable("modshift.suggest.for", s.forName).withStyle(ChatFormatting.AQUA));
			StringWidget sw = new StringWidget(label, this.font).setMaxWidth(w - (showDismiss() ? 82 : 58));
			sw.setX(left);
			sw.setY(y + 6);
			if (s.description != null) sw.setTooltip(Tooltip.create(Component.literal(s.description)));
			listWidget(sw);
			boolean done = added(s);
			Button add = Button.builder(Component.translatable(done ? "modshift.added" : "modshift.add"), b -> add(s, b))
					.bounds(left + w - (showDismiss() ? 78 : 54), y, 54, 20).build();
			add.active = !done;
			listWidget(add);
			if (showDismiss()) {
				Button x = Button.builder(Component.literal("×"), b -> {
					Config.dismissed.add(s.projectId);
					Config.save();
					items.remove(s);
					buildList();
				}).bounds(left + w - 20, y, 20, 20).tooltip(Tooltip.create(Component.translatable("modshift.suggest.hide"))).build();
				listWidget(x);
			}
			y += ROW;
		}
		pager(pages, bottom + 2, this::buildList);
	}

	private StringWidget text(Component c, int y) {
		StringWidget s = new StringWidget(c, this.font).setMaxWidth(listWidth());
		s.setX(listLeft());
		s.setY(y);
		return s;
	}

	private void add(Suggestion s, Button b) {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		b.active = false;
		b.setMessage(Component.literal("..."));
		CompletableFuture.runAsync(() -> {
			try {
				UpdateChecker.addProject(r, s.projectId, s.title);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((v, ex) -> this.minecraft.execute(() -> {
			if (ex != null) error = err(ex);
			buildList();
		}));
	}
}
