package com.tanto.modshift.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared layout helpers. Every screen is built from vanilla widgets only. */
abstract class BaseScreen extends Screen {
	protected static final int ROW = 22;
	protected final Screen parent;
	/** Widgets of the scrolling part, so it can be rebuilt without touching text fields. */
	private final List<GuiEventListener> listWidgets = new ArrayList<>();
	protected int page;

	protected BaseScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	protected void header(Component subtitle) {
		addCentered(this.title.copy().withStyle(ChatFormatting.BOLD), 10);
		if (subtitle != null) addCentered(subtitle.copy().withStyle(ChatFormatting.GRAY), 22);
	}

	protected StringWidget addCentered(Component text, int y) {
		StringWidget w = new StringWidget(text, this.font);
		w.setX(this.width / 2 - this.font.width(text) / 2);
		w.setY(y);
		return addRenderableWidget(w);
	}

	protected StringWidget addText(Component text, int x, int y, int maxWidth) {
		StringWidget w = new StringWidget(text, this.font).setMaxWidth(maxWidth);
		w.setX(x);
		w.setY(y);
		return addRenderableWidget(w);
	}

	protected MultiLineTextWidget addMessage(Component text, int y) {
		int w = Math.min(400, this.width - 20);
		MultiLineTextWidget m = new MultiLineTextWidget(text, this.font).setMaxWidth(w).setCentered(true);
		m.setX(this.width / 2 - w / 2);
		m.setY(y);
		return addRenderableWidget(m);
	}

	protected Button button(Component label, int x, int y, int w, Button.OnPress press) {
		return addRenderableWidget(Button.builder(label, press).bounds(x, y, w, 20).build());
	}

	protected int listWidth() {
		return Math.min(420, this.width - 20);
	}

	protected int listLeft() {
		return this.width / 2 - listWidth() / 2;
	}

	protected int rowsBetween(int top, int bottom) {
		return Math.max(2, (bottom - top) / ROW);
	}

	// ---- rebuildable list part ----

	protected <T extends GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T listWidget(T w) {
		listWidgets.add(w);
		return addRenderableWidget(w);
	}

	protected void clearList() {
		listWidgets.forEach(this::removeWidget);
		listWidgets.clear();
	}

	/** Adds "< 2 / 5 >" controls and returns nothing; calls onChange after a page switch. */
	protected void pager(int pages, int y, Runnable onChange) {
		if (pages <= 1) return;
		int cx = this.width / 2;
		Button prev = listWidget(Button.builder(Component.literal("<"), b -> { page--; onChange.run(); }).bounds(cx - 50, y, 20, 20).build());
		prev.active = page > 0;
		Component label = Component.literal((page + 1) + " / " + pages);
		StringWidget s = new StringWidget(label, this.font);
		s.setX(cx - this.font.width(label) / 2);
		s.setY(y + 6);
		listWidget(s);
		Button next = listWidget(Button.builder(Component.literal(">"), b -> { page++; onChange.run(); }).bounds(cx + 30, y, 20, 20).build());
		next.active = page < pages - 1;
	}

	/** Word-wraps plain text to the given pixel width. */
	protected List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		for (String para : text.replace("\r", "").split("\n", -1)) {
			if (para.isBlank()) { out.add(""); continue; }
			StringBuilder line = new StringBuilder();
			for (String word : para.split(" ")) {
				String cand = line.isEmpty() ? word : line + " " + word;
				if (this.font.width(cand) <= width) {
					line.setLength(0);
					line.append(cand);
				} else {
					if (!line.isEmpty()) out.add(line.toString());
					// very long words are cut
					String w = word;
					while (this.font.width(w) > width && w.length() > 1) {
						int cut = w.length() - 1;
						while (cut > 1 && this.font.width(w.substring(0, cut)) > width) cut--;
						out.add(w.substring(0, cut));
						w = w.substring(cut);
					}
					line.setLength(0);
					line.append(w);
				}
			}
			out.add(line.toString());
		}
		return out;
	}

	protected void openLink(String url) {
		if (url != null) ConfirmLinkScreen.confirmLinkNow(this, url);
	}

	static String fmtSize(long bytes) {
		return bytes > 1_048_576 ? String.format("%.1f MB", bytes / 1_048_576.0) : Math.max(1, bytes / 1024) + " KB";
	}

	static String fmtNum(long n) {
		if (n >= 999_500) return String.format("%.1fM", n / 1e6).replace(".0M", "M");
		if (n >= 1000) return Math.round(n / 1000.0) + "K";
		return String.valueOf(n);
	}

	static String err(Throwable e) {
		Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
		return c.getMessage() == null ? c.toString() : c.getMessage();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent);
	}
}
