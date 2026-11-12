package com.tanto.modshift.client;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

/** Lists available updates and runs the update. Built only from vanilla widgets. */
public class ModShiftScreen extends Screen {
	private static final int ROW = 22;
	private final Screen parent;
	private int page;
	private volatile boolean downloading;
	private volatile String progressText;
	private volatile String error;

	public ModShiftScreen(Screen parent) {
		super(Component.literal("ModShift"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		addCentered(Component.literal("ModShift").withStyle(ChatFormatting.BOLD), 10);
		addCentered(Component.translatable("modshift.subtitle", UpdateChecker.mcVersion(),
				UpdateChecker.loaders().get(0).equals("quilt") ? "Quilt" : "Fabric").withStyle(ChatFormatting.GRAY), 22);

		if (Installer.hasPendingPlan() && !downloading) {
			initPending(cx);
			return;
		}
		if (downloading) {
			addCentered(Component.literal(progressText == null ? "..." : progressText), this.height / 2 - 4);
			return;
		}
		if (error != null) {
			addMessage(Component.translatable("modshift.error", error).withStyle(ChatFormatting.RED), 50);
		}
		if (ModShiftClient.checking) {
			addCentered(Component.translatable("modshift.checking"), this.height / 2 - 20);
			addBottomButtons(cx, false);
			return;
		}
		if (ModShiftClient.lastError != null && error == null) {
			addMessage(Component.translatable("modshift.error", ModShiftClient.lastError).withStyle(ChatFormatting.RED), 50);
		}
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) {
			if (ModShiftClient.lastError == null) ModShiftClient.startCheck(this::rebuildWidgets);
			addBottomButtons(cx, false);
			return;
		}
		initList(r, cx);
	}

	private void initList(UpdateChecker.Result r, int cx) {
		MutableComponent status = Component.translatable("modshift.status", r.scanned, r.updates.size(), r.upToDate, r.unknown.size());
		StringWidget st = new StringWidget(status, this.font);
		st.setX(cx - this.font.width(status) / 2);
		st.setY(36);
		if (!r.unknown.isEmpty()) {
			st.setTooltip(Tooltip.create(Component.translatable("modshift.unknown", String.join(", ", r.unknown))));
		}
		addRenderableWidget(st);

		int top = 54;
		int listBottom = this.height - 64;
		int perPage = Math.max(2, (listBottom - top) / ROW);
		int pages = Math.max(1, (r.updates.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int listWidth = Math.min(420, this.width - 20);
		int left = cx - listWidth / 2;

		if (r.updates.isEmpty()) {
			addCentered(Component.translatable("modshift.none").withStyle(ChatFormatting.GREEN), top + 20);
		}
		List<UpdateChecker.Update> shown = r.updates.subList(page * perPage, Math.min(r.updates.size(), (page + 1) * perPage));
		int y = top;
		for (UpdateChecker.Update u : shown) {
			MutableComponent label;
			if (u.isDependency()) {
				label = Component.literal("+ " + u.title + "  ").append(Component.literal(u.newVersion).withStyle(ChatFormatting.GREEN));
			} else {
				label = Component.literal(u.title + "  ")
						.append(Component.literal(u.oldVersion == null ? "?" : u.oldVersion).withStyle(ChatFormatting.GRAY))
						.append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.literal(u.newVersion).withStyle(ChatFormatting.GREEN));
			}
			Checkbox cb = Checkbox.builder(label, this.font)
					.pos(left, y)
					.maxWidth(listWidth)
					.selected(u.selected)
					.onValueChange((c, v) -> u.selected = v)
					.build();
			String tip = u.isDependency()
					? Component.translatable("modshift.dep", u.requiredBy == null ? "?" : u.requiredBy).getString()
					: u.oldFile.getFileName() + "  →  " + u.fileName;
			cb.setTooltip(Tooltip.create(Component.literal(tip)));
			addRenderableWidget(cb);
			y += ROW;
		}

		if (pages > 1) {
			int py = listBottom + 2;
			Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
					.bounds(cx - 70, py, 20, 20).build());
			prev.active = page > 0;
			addCentered(Component.literal((page + 1) + " / " + pages), py + 6);
			Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
					.bounds(cx + 50, py, 20, 20).build());
			next.active = page < pages - 1;
		}
		addBottomButtons(cx, !r.updates.isEmpty());
	}

	private void addBottomButtons(int cx, boolean hasUpdates) {
		// Settings row
		int sy = this.height - 52;
		Checkbox beta = Checkbox.builder(Component.translatable("modshift.opt.beta"), this.font)
				.pos(cx - 205, sy + 2).selected(Config.includeBeta)
				.onValueChange((c, v) -> { Config.includeBeta = v; Config.save(); recheck(); })
				.build();
		beta.setTooltip(Tooltip.create(Component.translatable("modshift.opt.beta.tip")));
		addRenderableWidget(beta);
		Checkbox deps = Checkbox.builder(Component.translatable("modshift.opt.deps"), this.font)
				.pos(cx - 65, sy + 2).selected(Config.addDependencies)
				.onValueChange((c, v) -> { Config.addDependencies = v; Config.save(); recheck(); })
				.build();
		addRenderableWidget(deps);
		Checkbox startup = Checkbox.builder(Component.translatable("modshift.opt.startup"), this.font)
				.pos(cx + 75, sy + 2).selected(Config.checkOnStartup)
				.onValueChange((c, v) -> { Config.checkOnStartup = v; Config.save(); })
				.build();
		addRenderableWidget(startup);

		// Action row
		int by = this.height - 28;
		Button all = addRenderableWidget(Button.builder(Component.translatable("modshift.all"), b -> toggleAll())
				.bounds(cx - 206, by, 100, 20).build());
		all.active = hasUpdates;
		Button re = addRenderableWidget(Button.builder(Component.translatable("modshift.recheck"), b -> recheck())
				.bounds(cx - 102, by, 100, 20).build());
		re.active = !ModShiftClient.checking;
		Button go = addRenderableWidget(Button.builder(Component.translatable("modshift.go"), b -> confirm())
				.bounds(cx + 2, by, 100, 20)
				.tooltip(Tooltip.create(Component.translatable("modshift.go.tip")))
				.build());
		go.active = hasUpdates && selected().size() > 0;
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(cx + 106, by, 100, 20).build());
	}

	private void initPending(int cx) {
		addMessage(Component.translatable("modshift.pending.text", Installer.workDir().resolve("last-update.log").toString()), 50);
		addRenderableWidget(Button.builder(Component.translatable("modshift.pending.apply"), b -> {
			try {
				Installer.launchHelper();
				this.minecraft.stop();
			} catch (Exception e) {
				error = e.getMessage();
				rebuildWidgets();
			}
		}).bounds(cx - 154, this.height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("modshift.pending.discard"), b -> {
			try {
				Files.deleteIfExists(Installer.planFile());
			} catch (Exception ignored) {
			}
			rebuildWidgets();
		}).bounds(cx + 4, this.height - 28, 150, 20).build());
	}

	private List<UpdateChecker.Update> selected() {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		return r == null ? List.of() : r.updates.stream().filter(u -> u.selected).toList();
	}

	private void toggleAll() {
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		boolean any = r.updates.stream().anyMatch(u -> !u.selected);
		r.updates.forEach(u -> u.selected = any);
		rebuildWidgets();
	}

	private void recheck() {
		error = null;
		ModShiftClient.lastResult = null;
		ModShiftClient.startCheck(this::rebuildWidgets);
		rebuildWidgets();
	}

	private void confirm() {
		List<UpdateChecker.Update> sel = selected();
		if (sel.isEmpty()) return;
		long bytes = sel.stream().mapToLong(u -> u.size).sum();
		String size = bytes > 1_048_576 ? String.format("%.1f MB", bytes / 1_048_576.0) : Math.max(1, bytes / 1024) + " KB";
		this.minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
			this.minecraft.setScreenAndShow(this);
			if (yes) start(sel);
		}, Component.translatable("modshift.confirm.title"),
				Component.translatable("modshift.confirm.text", sel.size(), size, "modshift/backup"),
				Component.translatable("modshift.go"), Component.translatable("gui.cancel")));
	}

	private void start(List<UpdateChecker.Update> sel) {
		downloading = true;
		error = null;
		progressText = Component.translatable("modshift.downloading", 0, sel.size()).getString();
		rebuildWidgets();
		CompletableFuture.runAsync(() -> {
			try {
				Installer.download(sel, (done, total) -> {
					progressText = Component.translatable("modshift.downloading", done, total).getString();
					this.minecraft.execute(this::rebuildWidgets);
				});
				this.minecraft.execute(() -> {
					try {
						progressText = Component.translatable("modshift.closing").getString();
						rebuildWidgets();
						Installer.launchHelper();
						this.minecraft.stop();
					} catch (Exception e) {
						fail(e);
					}
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void fail(Exception e) {
		ModShiftClient.LOG.error("ModShift update failed", e);
		downloading = false;
		error = e.getMessage() == null ? e.toString() : e.getMessage();
		try {
			Files.deleteIfExists(Installer.planFile());
		} catch (Exception ignored) {
		}
		rebuildWidgets();
	}

	private void addCentered(Component text, int y) {
		StringWidget w = new StringWidget(text, this.font);
		w.setX(this.width / 2 - this.font.width(text) / 2);
		w.setY(y);
		addRenderableWidget(w);
	}

	private void addMessage(Component text, int y) {
		int w = Math.min(400, this.width - 20);
		MultiLineTextWidget m = new MultiLineTextWidget(text, this.font).setMaxWidth(w).setCentered(true);
		m.setX(this.width / 2 - w / 2);
		m.setY(y);
		addRenderableWidget(m);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !downloading;
	}

	@Override
	public void onClose() {
		if (!downloading) this.minecraft.setScreenAndShow(parent);
	}
}
