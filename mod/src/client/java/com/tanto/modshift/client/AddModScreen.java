package com.tanto.modshift.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import com.tanto.modshift.client.UpdateChecker.Suggestion;

/** Search Modrinth and add mods to the pack without downloading jars by hand. */
class AddModScreen extends SuggestionsScreen {
	private static String lastQuery = "";
	private EditBox box;

	AddModScreen(ModShiftScreen parent) {
		super(Component.translatable("modshift.add.title"), parent);
	}

	@Override
	protected void init() {
		header(Component.translatable("modshift.add.sub", UpdateChecker.mcVersion()));
		int cx = this.width / 2;
		box = new EditBox(this.font, cx - 206, 36, 306, 20, Component.translatable("modshift.add.hint"));
		box.setHint(Component.translatable("modshift.add.hint").withStyle(ChatFormatting.DARK_GRAY));
		box.setMaxLength(100);
		box.setValue(lastQuery);
		addRenderableWidget(box);
		setInitialFocus(box);
		button(Component.translatable("modshift.search"), cx + 106, 36, 100, b -> runSearch());
		button(Component.translatable("gui.back"), cx - 50, this.height - 28, 100, b -> onClose());
		buildList();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (box != null && box.isFocused() && (event.key() == 257 || event.key() == 335)) { // Enter
			runSearch();
			return true;
		}
		return super.keyPressed(event);
	}

	private void runSearch() {
		String q = box.getValue().trim();
		lastQuery = q;
		if (q.isEmpty()) return;
		UpdateChecker.Result r = ModShiftClient.lastResult;
		if (r == null) return;
		loading = true;
		error = null;
		buildList();
		CompletableFuture.supplyAsync(() -> {
			try {
				return UpdateChecker.search(r, q);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((List<Suggestion> list, Throwable ex) -> this.minecraft.execute(() -> {
			loading = false;
			items.clear();
			if (ex != null) error = err(ex);
			else items.addAll(list);
			page = 0;
			buildList();
		}));
	}

	@Override
	protected boolean showDismiss() {
		return false;
	}

	@Override
	protected int listTop() {
		return 62;
	}
}
