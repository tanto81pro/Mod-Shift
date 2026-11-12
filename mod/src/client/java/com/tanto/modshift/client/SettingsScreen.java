package com.tanto.modshift.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** ModShift settings. Changing what is checked runs the check again. */
class SettingsScreen extends BaseScreen {
	private final String before;
	private EditBox key;
	private EditBox proxy;

	SettingsScreen(ModShiftScreen parent) {
		super(Component.translatable("modshift.settings"), parent);
		before = snapshot();
	}

	private static String snapshot() {
		return Config.includeBeta + "|" + Config.includeAlpha + "|" + Config.addDependencies + "|" + Config.nameSearch + "|" + Config.cfKey + "|" + Config.cfProxy + "|" + Config.ignored;
	}

	@Override
	protected void init() {
		header(null);
		int left = this.width / 2 - 150;
		int y = 32;
		checkbox("modshift.opt.beta", Config.includeBeta, v -> Config.includeBeta = v, left, y, "modshift.opt.beta.tip");
		checkbox("modshift.opt.alpha", Config.includeAlpha, v -> Config.includeAlpha = v, left + 150, y, null);
		y += 22;
		checkbox("modshift.opt.deps", Config.addDependencies, v -> Config.addDependencies = v, left, y, null);
		checkbox("modshift.opt.startup", Config.checkOnStartup, v -> Config.checkOnStartup = v, left + 150, y, null);
		y += 22;
		checkbox("modshift.opt.nameSearch", Config.nameSearch, v -> Config.nameSearch = v, left, y, "modshift.opt.nameSearch.tip");
		y += 30;

		addText(Component.translatable("modshift.opt.cfKey"), left, y, 300);
		y += 12;
		key = new EditBox(this.font, left, y, 300, 20, Component.translatable("modshift.opt.cfKey"));
		key.setMaxLength(200);
		key.setValue(Config.cfKey);
		key.setHint(Component.translatable("modshift.opt.cfKey.hint").withStyle(ChatFormatting.DARK_GRAY));
		key.setTooltip(Tooltip.create(Component.translatable("modshift.opt.cfKey.tip")));
		addRenderableWidget(key);
		y += 26;
		addText(Component.translatable("modshift.opt.cfProxy"), left, y, 300);
		y += 12;
		proxy = new EditBox(this.font, left, y, 300, 20, Component.translatable("modshift.opt.cfProxy"));
		proxy.setMaxLength(300);
		proxy.setValue(Config.cfProxy);
		proxy.setHint(Component.literal("https://...workers.dev").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(proxy);
		y += 26;

		var reset = button(Component.translatable("modshift.opt.resetIgnored", Config.ignored.size() + Config.dismissed.size()), left, y, 300, b -> {
			Config.ignored.clear();
			Config.dismissed.clear();
			rebuildWidgets();
		});
		reset.active = !Config.ignored.isEmpty() || !Config.dismissed.isEmpty();

		button(Component.translatable("gui.done"), this.width / 2 - 50, this.height - 28, 100, b -> onClose());
	}

	private void checkbox(String key, boolean value, java.util.function.Consumer<Boolean> set, int x, int y, String tip) {
		Checkbox c = Checkbox.builder(Component.translatable(key), this.font).pos(x, y).maxWidth(146).selected(value)
				.onValueChange((cb, v) -> set.accept(v)).build();
		if (tip != null) c.setTooltip(Tooltip.create(Component.translatable(tip)));
		addRenderableWidget(c);
	}

	@Override
	public void onClose() {
		if (key != null) Config.cfKey = key.getValue().trim();
		if (proxy != null) Config.cfProxy = proxy.getValue().trim();
		Config.save();
		boolean changed = !before.equals(snapshot());
		super.onClose();
		if (changed && parent instanceof ModShiftScreen m) m.recheck();
	}
}
