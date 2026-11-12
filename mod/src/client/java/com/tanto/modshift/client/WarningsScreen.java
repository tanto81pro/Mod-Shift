package com.tanto.modshift.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

/** Conflicts, missing dependencies, CurseForge errors and files nobody recognised. */
class WarningsScreen extends BaseScreen {
	WarningsScreen(ModShiftScreen parent) {
		super(Component.translatable("modshift.warnings.title"), parent);
	}

	@Override
	protected void init() {
		header(null);
		button(Component.translatable("gui.back"), this.width / 2 - 50, this.height - 28, 100, b -> onClose());
		buildText();
	}

	private void buildText() {
		clearList();
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		int w = listWidth();
		List<Component> lines = new ArrayList<>();
		if (!r.warnings.isEmpty()) {
			for (String warning : r.warnings) {
				for (String l : wrap("• " + ModShiftScreen.noteText(warning, r).getString(), w)) {
					lines.add(Component.literal(l).withStyle(warning.startsWith("§conflict") ? ChatFormatting.RED : ChatFormatting.GOLD));
				}
			}
			lines.add(Component.empty());
		}
		if (!r.unknown.isEmpty()) {
			for (String l : wrap(Component.translatable("modshift.warnings.unknown").getString(), w)) {
				lines.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
			}
			for (String f : r.unknown) lines.add(Component.literal("  " + f));
		}
		int top = 30;
		int bottom = this.height - 56;
		int per = Math.max(3, (bottom - top) / 11);
		int pages = Math.max(1, (lines.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		int y = top;
		for (Component c : lines.subList(page * per, Math.min(lines.size(), (page + 1) * per))) {
			StringWidget s = new StringWidget(c, this.font).setMaxWidth(w);
			s.setX(listLeft());
			s.setY(y);
			listWidget(s);
			y += 11;
		}
		pager(pages, bottom + 4, this::buildText);
	}
}
