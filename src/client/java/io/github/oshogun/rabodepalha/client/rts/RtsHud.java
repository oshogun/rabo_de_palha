package io.github.oshogun.rabodepalha.client.rts;

import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsScreenRect;

/** RTS mode HUD: the number of selected workers, and the selection box while dragging. */
public final class RtsHud {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BOX_FILL_COLOR = 0x3380FF80;
	private static final int BOX_BORDER_COLOR = 0xFF80FF80;
	private static final int MARGIN = 4;

	private RtsHud() {
	}

	public static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RaboDePalha.id("rts_selection"),
				RtsHud::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!RtsClient.isActive()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int count = RtsSelectionClient.count();
		if (count > 0) {
			graphics.text(client.font, Component.translatable("rabodepalha.rts.selected", count), MARGIN, MARGIN,
					TEXT_COLOR);
		}

		RtsScreenRect rect = RtsSelectionClient.currentDragRect();
		if (rect != null) {
			// The box is in window pixels; the HUD draws in GUI-scaled coordinates.
			Window window = client.getWindow();
			double scale = window.getGuiScale();
			int x0 = (int) Math.floor(rect.minX() / scale);
			int y0 = (int) Math.floor(rect.minY() / scale);
			int x1 = (int) Math.ceil(rect.maxX() / scale);
			int y1 = (int) Math.ceil(rect.maxY() / scale);
			graphics.fill(x0, y0, x1, y1, BOX_FILL_COLOR);
			graphics.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), BOX_BORDER_COLOR);
		}
	}
}
