package com.tanto.modshift.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.tanto.modshift.client.UpdateChecker.Entry;
import com.tanto.modshift.client.UpdateChecker.Kind;

/** The main ModShift screen: what will change, with filters, details and the update button. */
public class ModShiftScreen extends BaseScreen {
	private enum Filter { ALL, UPDATES, ADDS, REMOVALS, MANUAL }

	private Filter filter = Filter.ALL;
	private String query = "";
	private volatile boolean busy;
	private volatile String progressText;
	private volatile String error;
	private Button goButton;

	public ModShiftScreen(Screen parent) {
		super(Component.literal("ModShift"), parent);
	}

	@Override
	protected void init() {
		header(Component.translatable("modshift.subtitle", UpdateChecker.mcVersion(), UpdateChecker.quilt() ? "Quilt" : "Fabric"));
		int cx = this.width / 2;

		if (busy) {
			addCentered(Component.literal(progressText == null ? "..." : progressText), this.height / 2 - 4);
			return;
		}
		if (Installer.hasPendingPlan()) {
			initPending(cx);
			return;
		}
		String problem = error != null ? error : ModShiftClient.lastError;
		if (problem != null) {
			addMessage(Component.translatable("modshift.error", problem).withStyle(ChatFormatting.RED), 36);
		}
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (ModShiftClient.checking || r == null) {
			if (!ModShiftClient.checking && problem == null) ModShiftClient.startCheck(this::rebuildWidgets);
			if (ModShiftClient.checking) addCentered(Component.translatable("modshift.checking"), this.height / 2 - 20);
			bottomButtons(cx, null);
			button(Component.translatable("modshift.settings"), cx - 50, this.height - 54, 100, b -> this.minecraft.setScreenAndShow(new SettingsScreen(this)));
			return;
		}

		// Summary line
		long dl = r.entries.stream().filter(Entry::downloads).count();
		long rm = r.entries.stream().filter(e -> e.kind == Kind.REMOVE).count();
		MutableComponent status = Component.translatable("modshift.status", r.scanned, dl, rm, r.upToDate, r.unknown.size());
		StringWidget st = addCentered(status, 34);
		if (!r.unknown.isEmpty()) st.setTooltip(Tooltip.create(Component.translatable("modshift.unknown", String.join(", ", r.unknown))));

		// Toolbar
		int tb = 46;
		int problems = r.warnings.size() + r.unknown.size();
		button(Component.translatable("modshift.tab.suggest", r.optional.size()), cx - 206, tb, 100, b -> this.minecraft.setScreenAndShow(new SuggestionsScreen(this)));
		button(Component.translatable("modshift.tab.add"), cx - 102, tb, 100, b -> this.minecraft.setScreenAndShow(new AddModScreen(this)));
		Button warn = button(Component.translatable("modshift.tab.warnings", problems)
				.withStyle(r.warnings.isEmpty() ? ChatFormatting.RESET : ChatFormatting.GOLD), cx + 2, tb, 100,
				b -> this.minecraft.setScreenAndShow(new WarningsScreen(this)));
		warn.active = problems > 0;
		button(Component.translatable("modshift.settings"), cx + 106, tb, 100, b -> this.minecraft.setScreenAndShow(new SettingsScreen(this)));

		// Filter row
		int fy = 70;
		EditBox search = new EditBox(this.font, cx - 206, fy, 200, 20, Component.translatable("modshift.filter"));
		search.setHint(Component.translatable("modshift.filter").withStyle(ChatFormatting.DARK_GRAY));
		search.setValue(query);
		search.setResponder(v -> { query = v; page = 0; buildList(); });
		addRenderableWidget(search);
		button(filterLabel(), cx - 2, fy, 120, b -> {
			filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
			page = 0;
			b.setMessage(filterLabel());
			buildList();
		});

		bottomButtons(cx, r);
		buildList();
	}

	private Component filterLabel() {
		return Component.translatable("modshift.filter." + filter.name().toLowerCase(Locale.ROOT));
	}

	private List<Entry> visible(UpdateChecker.Result r) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		return r.entries.stream().filter(e -> switch (filter) {
			case ALL -> true;
			case UPDATES -> e.kind == Kind.UPDATE;
			case ADDS -> e.kind == Kind.ADD || e.kind == Kind.DEPENDENCY;
			case REMOVALS -> e.kind == Kind.REMOVE;
			case MANUAL -> e.kind == Kind.MANUAL;
		}).filter(e -> q.isEmpty() || (e.title + " " + (e.oldFile == null ? "" : e.oldFile.getFileName())).toLowerCase(Locale.ROOT).contains(q)).toList();
	}

	private void buildList() {
		clearList();
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		int top = 96;
		int bottom = this.height - 58;
		int perPage = rowsBetween(top, bottom);
		List<Entry> rows = visible(r);
		int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		int left = listLeft();
		int w = listWidth();

		if (r.entries.isEmpty()) {
			listWidget(centered(Component.translatable("modshift.none").withStyle(ChatFormatting.GREEN), top + 20));
		} else if (rows.isEmpty()) {
			listWidget(centered(Component.translatable("modshift.nomatch").withStyle(ChatFormatting.GRAY), top + 20));
		}
		int y = top;
		for (Entry e : rows.subList(page * perPage, Math.min(rows.size(), (page + 1) * perPage))) {
			Checkbox cb = Checkbox.builder(label(e, r), this.font)
					.pos(left, y)
					.maxWidth(w - 26)
					.selected(e.selected && e.kind != Kind.MANUAL)
					.onValueChange((c, v) -> { e.selected = v; updateGo(); })
					.build();
			cb.active = e.kind != Kind.MANUAL;
			cb.setTooltip(Tooltip.create(tooltip(e, r)));
			listWidget(cb);
			if (e.kind != Kind.REMOVE) {
				Button info = Button.builder(Component.literal("i"), b -> this.minecraft.setScreenAndShow(new DetailsScreen(this, e)))
						.bounds(left + w - 20, y, 20, 20)
						.tooltip(Tooltip.create(Component.translatable("modshift.details")))
						.build();
				listWidget(info);
			}
			y += ROW;
		}
		pager(pages, bottom + 2, this::buildList);
		updateGo();
	}

	private StringWidget centered(Component text, int y) {
		StringWidget s = new StringWidget(text, this.font);
		s.setX(this.width / 2 - this.font.width(text) / 2);
		s.setY(y);
		return s;
	}

	static MutableComponent label(Entry e, UpdateChecker.Result r) {
		String t = e.title == null ? "?" : e.title;
		return switch (e.kind) {
			case UPDATE -> Component.literal(t + "  ")
					.append(Component.literal(e.oldVersion == null ? "?" : e.oldVersion).withStyle(ChatFormatting.GRAY))
					.append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(e.newVersion).withStyle(e.byName ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
			case DEPENDENCY, ADD -> Component.literal("+ ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(t + "  ").withStyle(ChatFormatting.RESET))
					.append(Component.literal(e.newVersion).withStyle(ChatFormatting.GREEN));
			case REMOVE -> Component.literal("− ").withStyle(ChatFormatting.RED)
					.append(Component.literal(t + "  ").withStyle(ChatFormatting.RESET))
					.append(noteText(e.note, r).copy().withStyle(ChatFormatting.GRAY));
			case MANUAL -> Component.literal(t + "  ")
					.append(Component.literal(e.newVersion).withStyle(ChatFormatting.GOLD))
					.append(Component.literal("  ").append(Component.translatable("modshift.manual.short")).withStyle(ChatFormatting.GOLD));
		};
	}

	static Component tooltip(Entry e, UpdateChecker.Result r) {
		MutableComponent c = Component.empty();
		if (e.oldFile != null) c.append(Component.literal(e.oldFile.getFileName().toString()));
		if (e.fileName != null) c.append(Component.literal((e.oldFile != null ? "  →  " : "") + e.fileName));
		if (e.size > 0) c.append(Component.literal("  (" + fmtSize(e.size) + ")"));
		if (e.source != null) c.append(Component.literal("\n" + ("cf".equals(e.source) ? "CurseForge" : "Modrinth")).withStyle(ChatFormatting.GRAY));
		if (e.note != null) c.append(Component.literal("\n")).append(noteText(e.note, r));
		return c;
	}

	/** Turns the short codes stored in notes and warnings into readable text. */
	static Component noteText(String note, UpdateChecker.Result r) {
		if (note == null || !note.startsWith("§")) return Component.literal(note == null ? "" : note);
		String[] p = note.substring(1).split(":", 3);
		java.util.function.Function<String, String> name = id -> r == null ? id : r.titles.getOrDefault(id, id);
		return switch (p[0]) {
			case "broken" -> Component.translatable("modshift.note.broken");
			case "wrongLoader" -> Component.translatable("modshift.note.wrongLoader", p.length > 1 ? p[1] : "?");
			case "duplicate" -> Component.translatable("modshift.note.duplicate");
			case "byName" -> Component.translatable("modshift.note.byName");
			case "manual" -> Component.translatable("modshift.note.manual");
			case "orphan" -> Component.translatable("modshift.note.orphan");
			case "requiredBy" -> Component.translatable("modshift.note.requiredBy", name.apply(p[1]));
			case "conflict" -> Component.translatable("modshift.warn.conflict", p[1], p.length > 2 ? p[2] : "?");
			case "missingDep" -> Component.translatable("modshift.warn.missingDep", name.apply(p[1]), p.length > 2 ? name.apply(p[2]) : "?");
			case "cfError" -> Component.translatable("modshift.warn.cfError", note.substring(note.indexOf(':') + 1));
			default -> Component.literal(note);
		};
	}

	private void bottomButtons(int cx, UpdateChecker.Result r) {
		int by = this.height - 28;
		Button all = button(Component.translatable("modshift.all"), cx - 206, by, 100, b -> toggleAll());
		all.active = r != null && !r.entries.isEmpty();
		Button re = button(Component.translatable("modshift.recheck"), cx - 102, by, 100, b -> recheck());
		re.active = !ModShiftClient.checking;
		goButton = addRenderableWidget(Button.builder(Component.translatable("modshift.go"), b -> confirm())
				.bounds(cx + 2, by, 100, 20)
				.tooltip(Tooltip.create(Component.translatable("modshift.go.tip")))
				.build());
		updateGo();
		button(Component.translatable("gui.back"), cx + 106, by, 100, b -> onClose());
	}

	private void updateGo() {
		if (goButton != null) goButton.active = !chosen().isEmpty();
	}

	private List<Entry> chosen() {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		return r == null ? List.of() : r.entries.stream().filter(e -> e.selected && e.kind != Kind.MANUAL).toList();
	}

	private void toggleAll() {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		List<Entry> shown = visible(r).stream().filter(e -> e.kind != Kind.MANUAL).toList();
		boolean any = shown.stream().anyMatch(e -> !e.selected);
		shown.forEach(e -> e.selected = any);
		buildList();
	}

	void recheck() {
		error = null;
		ModShiftClient.lastResult = null;
		ModShiftClient.lastError = null;
		ModShiftClient.startCheck(this::rebuildWidgets);
		rebuildWidgets();
	}

	private void initPending(int cx) {
		addMessage(Component.translatable("modshift.pending.text", Installer.workDir().resolve("last-update.log").toString()), 50);
		button(Component.translatable("modshift.pending.apply"), cx - 154, this.height - 28, 150, b -> {
			try {
				Installer.launchHelper();
				this.minecraft.stop();
			} catch (Exception e) {
				error = err(e);
				rebuildWidgets();
			}
		});
		button(Component.translatable("modshift.pending.discard"), cx + 4, this.height - 28, 150, b -> {
			try {
				Files.deleteIfExists(Installer.planFile());
			} catch (Exception ignored) {
			}
			rebuildWidgets();
		});
	}

	private void confirm() {
		List<Entry> sel = chosen();
		if (sel.isEmpty()) return;
		long n = sel.stream().filter(Entry::downloads).count();
		long rm = sel.stream().filter(e -> e.kind == Kind.REMOVE).count();
		long bytes = sel.stream().filter(Entry::downloads).mapToLong(e -> e.size).sum();
		UpdateChecker.Result r = ModShiftClient.lastResult;
		boolean conflicts = r != null && r.warnings.stream().anyMatch(w -> w.startsWith("§conflict"));
		MutableComponent text = Component.translatable("modshift.confirm.text", n, fmtSize(bytes), rm, "modshift/backup");
		if (conflicts) text.append("\n\n").append(Component.translatable("modshift.confirm.conflicts").withStyle(ChatFormatting.GOLD));
		this.minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
			this.minecraft.setScreenAndShow(this);
			if (yes) start(sel);
		}, Component.translatable("modshift.confirm.title"), text,
				Component.translatable("modshift.go"), Component.translatable("gui.cancel")));
	}

	private void start(List<Entry> sel) {
		busy = true;
		error = null;
		progressText = Component.translatable("modshift.downloading", 0, sel.size()).getString();
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				return Installer.prepare(sel, (done, total) -> {
					progressText = Component.translatable("modshift.downloading", done, total).getString();
					this.minecraft.execute(this::rebuildWidgets);
				});
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((staged, ex) -> this.minecraft.execute(() -> {
			if (ex != null) { fail(ex); return; }
			List<String> loader = Installer.loaderProblems(staged);
			if (loader.isEmpty()) { closeAndSwap(); return; }
			String[] v = loader.get(0).split("\\|");
			this.minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
				if (yes) closeAndSwap();
				else { cancelPlan(); this.minecraft.setScreenAndShow(this); }
			}, Component.translatable("modshift.loader.title"),
					Component.translatable("modshift.loader.text", v[0], v.length > 1 ? v[1] : "?", "fabricmc.net/use/installer"),
					Component.translatable("modshift.loader.continue"), Component.translatable("gui.cancel")));
		}));
	}

	private void closeAndSwap() {
		try {
			progressText = Component.translatable("modshift.closing").getString();
			this.minecraft.setScreenAndShow(this);
			Installer.launchHelper();
			this.minecraft.stop();
		} catch (Exception e) {
			fail(e);
		}
	}

	private void cancelPlan() {
		busy = false;
		try {
			Files.deleteIfExists(Installer.planFile());
		} catch (Exception ignored) {
		}
	}

	private void fail(Throwable e) {
		ModShiftClient.LOG.error("ModShift update failed", e);
		cancelPlan();
		error = err(e);
		rebuildWidgets();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !busy;
	}

	@Override
	public void onClose() {
		if (!busy) super.onClose();
	}

	static Path logFile() {
		return Installer.workDir().resolve("last-update.log");
	}
}
