package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

/**
 * Client side of RTS orders. An order sends the clicked block and face for the selected workers; the server
 * derives the target, checks the order and answers through the HUD. A plain right-click is a move; other kinds
 * come from the command bar. Shift appends to each worker's queue instead of replacing it. Orders that
 * {@link RtsOrderHint} already knows will be rejected are refused locally with the same reason the HUD hint
 * shows.
 */
public final class RtsOrdersClient {
	private RtsOrdersClient() {
	}

	/** A plain right-click: move the selected workers to the clicked block. */
	public static void onRightClick(double x, double y, boolean shift) {
		// The order targets the block the ray hits; a worker under the cursor is ignored.
		RtsSelectionClient.Hover hover = RtsSelectionClient.hoverAt(x, y);
		sendOrder(RtsOrderKind.MOVE, hover, RtsOrderHintClient.current(hover, RtsOrderKind.MOVE), shift);
	}

	/** Sends an order of {@code kind} at {@code hover}, or shows why {@code hint} rejects it. */
	public static void sendOrder(RtsOrderKind kind, RtsSelectionClient.Hover hover, RtsOrderHint.Hint hint,
			boolean shift) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		BlockHitResult hit = hover.block();
		if (!hint.valid() || hit == null) {
			RtsHud.showFeedback(false, RtsOrderHintClient.text(hint, hover));
			return;
		}
		int slot = client.player.getInventory().getSelectedSlot();
		if (ClientPlayNetworking.canSend(RtsOrderPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsOrderPayload(RtsSelectionClient.selectedIds(), kind,
					hit.getBlockPos(), hit.getDirection(), slot, shift));
		}
	}

	/** Clears the selected workers' queues and current tasks, like {@code /rdp clear}. */
	public static void sendStop() {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsStopPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsStopPayload(RtsSelectionClient.selectedIds()));
		}
	}

	/** Spawns a worker on top of the hovered block; the caller has checked the spawn hint. */
	public static void sendSpawn(RtsSelectionClient.Hover hover) {
		BlockHitResult hit = hover.block();
		if (hit == null) {
			return;
		}
		if (ClientPlayNetworking.canSend(RtsSpawnPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsSpawnPayload(hit.getBlockPos()));
		}
	}
}
