package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;

/**
 * Client side of RTS orders. A right-click sends the clicked block and face for the selected workers; the
 * server derives the target, checks the order and answers through the HUD. The place modifier wins over the
 * mine modifier, and shift appends to each worker's queue instead of replacing it. Orders that
 * {@link RtsOrderHint} already knows will be rejected are refused locally with the same reason the HUD hint
 * shows.
 */
public final class RtsOrdersClient {
	private RtsOrdersClient() {
	}

	public static void onRightClick(double x, double y, boolean shift) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// The order targets the block the ray hits; a worker under the cursor is ignored.
		RtsSelectionClient.Hover hover = RtsSelectionClient.hoverAt(x, y);
		RtsOrderHint.Hint hint = RtsOrderHintClient.current(hover);
		BlockHitResult hit = hover.block();
		if (!hint.valid() || hit == null) {
			RtsHud.showFeedback(false, RtsOrderHintClient.text(hint, hover));
			return;
		}
		int slot = client.player.getInventory().getSelectedSlot();
		if (ClientPlayNetworking.canSend(RtsOrderPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsOrderPayload(RtsSelectionClient.selectedIds(), hint.kind(),
					hit.getBlockPos(), hit.getDirection(), slot, shift));
		}
	}
}
