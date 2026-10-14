package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.rts.RtsPanel;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;

/**
 * Client side of the RTS side panel: which layout the watched workers get, and what a left press on it does. A
 * row narrows the selection to its worker, Remove drops a queued task, Clear stops the worker, an item cell
 * picks (or unpicks) that item; every press on
 * the panel is consumed, like presses on the command bar.
 */
public final class RtsPanelClient {
	private RtsPanelClient() {
	}

	/** The panel's layout on the current window, or null when no worker is watched. */
	public static RtsPanel.@Nullable Layout layout() {
		List<Integer> watched = RtsSnapshotsClient.watched();
		if (watched.isEmpty()) {
			return null;
		}
		Window window = Minecraft.getInstance().getWindow();
		int guiW = window.getGuiScaledWidth();
		int guiH = window.getGuiScaledHeight();
		if (watched.size() > 1) {
			return RtsPanel.multi(watched.size(), RtsItemPickClient.combined().size(), guiW, guiH);
		}
		WorkerSnapshot snapshot = RtsSnapshotsClient.get(watched.get(0));
		if (snapshot == null) {
			return RtsPanel.single(0, 0, guiW, guiH);
		}
		return RtsPanel.single(snapshot.queued().size(), snapshot.inventory().size(), guiW, guiH);
	}

	/** What the window pixel ({@code x}, {@code y}) falls on in the panel. */
	public static RtsPanel.Hit hitAt(double x, double y) {
		RtsPanel.Layout layout = layout();
		if (layout == null) {
			return RtsPanel.Hit.NONE;
		}
		double scale = Minecraft.getInstance().getWindow().getGuiScale();
		return RtsPanel.hitTest(layout, RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/** What the mouse cursor is on in the panel. */
	public static RtsPanel.Hit hovered() {
		Minecraft client = Minecraft.getInstance();
		if (client.mouseHandler == null) {
			return RtsPanel.Hit.NONE;
		}
		return hitAt(client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	/** Whether the window pixel ({@code x}, {@code y}) is on the panel. */
	public static boolean over(double x, double y) {
		return !(hitAt(x, y) instanceof RtsPanel.Hit.None);
	}

	/** Handles a left press. Returns true when it fell on the panel, which consumes it. */
	public static boolean onLeftPress(double x, double y) {
		RtsPanel.Hit hit = hitAt(x, y);
		List<Integer> watched = RtsSnapshotsClient.watched();
		switch (hit) {
			case RtsPanel.Hit.None none -> {
				return false;
			}
			case RtsPanel.Hit.Row row -> {
				if (row.index() < watched.size()) {
					RtsSelectionClient.selectOnly(watched.get(row.index()));
				}
			}
			case RtsPanel.Hit.Remove remove -> {
				WorkerSnapshot snapshot = watched.size() == 1 ? RtsSnapshotsClient.get(watched.get(0)) : null;
				if (snapshot != null && remove.index() < snapshot.queued().size()) {
					RtsOrdersClient.sendRemoveTask(snapshot.entityId(), snapshot.queued().get(remove.index()).taskId());
				}
			}
			case RtsPanel.Hit.Clear clear -> {
				if (watched.size() == 1) {
					RtsOrdersClient.sendStop(List.of(watched.get(0)));
				}
			}
			case RtsPanel.Hit.Item item -> {
				if (watched.size() == 1) {
					WorkerSnapshot snapshot = RtsSnapshotsClient.get(watched.get(0));
					if (snapshot != null && item.index() < snapshot.inventory().size()) {
						ItemStack stack = snapshot.inventory().get(item.index());
						if (!stack.isEmpty()) {
							RtsItemPickClient.pick(BuiltInRegistries.ITEM.getKey(stack.getItem()));
						}
					}
				} else {
					List<RtsItemPicker.Entry> combined = RtsItemPickClient.combined();
					if (item.index() < combined.size()) {
						RtsItemPickClient.pick(combined.get(item.index()).item());
					}
				}
			}
			case RtsPanel.Hit.Body body -> {
				// Consumed: a press on the panel's background must not select or order anything.
			}
		}
		return true;
	}
}
