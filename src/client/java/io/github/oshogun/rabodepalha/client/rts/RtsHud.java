package io.github.oshogun.rabodepalha.client.rts;

import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsScreenRect;

/**
 * RTS mode HUD: the number of selected workers, the outcome of the last order, the block a place order
 * would use, and the selection box while dragging.
 */
public final class RtsHud {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BOX_FILL_COLOR = 0x3380FF80;
	private static final int BOX_BORDER_COLOR = 0xFF80FF80;
	private static final int MARGIN = 4;

	private RtsHud() {
	}

	private static final int OK_COLOR = 0xFF55FF55;
	private static final int ERROR_COLOR = 0xFFFF5555;
	/** How long an order feedback line stays on screen (about 60 ticks). */
	private static final long FEEDBACK_MILLIS = 3000L;

	private static boolean feedbackOk;
	private static Component feedback;
	private static long feedbackExpiresAt;

	/** Shows the outcome of an RTS order for a few seconds. */
	public static void showFeedback(boolean ok, Component message) {
		feedbackOk = ok;
		feedback = message;
		feedbackExpiresAt = System.currentTimeMillis() + FEEDBACK_MILLIS;
	}

	public static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RaboDePalha.id("rts_selection"),
				RtsHud::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!RtsClient.isActive()) {
			// Leaving RTS mode drops any pending order feedback.
			feedback = null;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int lineHeight = client.font.lineHeight + 2;
		int y = MARGIN;
		int count = RtsSelectionClient.count();
		if (count > 0) {
			graphics.text(client.font, Component.translatable("rabodepalha.rts.selected", count), MARGIN, y,
					TEXT_COLOR);
			y += lineHeight;
		}
		if (feedback != null) {
			if (System.currentTimeMillis() < feedbackExpiresAt) {
				graphics.text(client.font, feedback, MARGIN, y, feedbackOk ? OK_COLOR : ERROR_COLOR);
				y += lineHeight;
			} else {
				feedback = null;
			}
		}
		if (client.player != null) {
			ItemStack held = client.player.getInventory().getItem(client.player.getInventory().getSelectedSlot());
			if (held.getItem() instanceof BlockItem) {
				graphics.text(client.font, Component.translatable("rabodepalha.rts.place_block", held.getHoverName()),
						MARGIN, y, TEXT_COLOR);
			}
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
