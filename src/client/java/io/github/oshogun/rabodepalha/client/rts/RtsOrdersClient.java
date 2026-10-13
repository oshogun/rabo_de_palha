package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

/**
 * Client side of RTS orders. A right-click sends the clicked block and face for the selected workers; the
 * server derives the target, checks the order and answers through the HUD. The place modifier wins over the
 * mine modifier, and shift appends to each worker's queue instead of replacing it.
 */
public final class RtsOrdersClient {
	private RtsOrdersClient() {
	}

	public static void onRightClick(double x, double y, boolean shift) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		if (RtsSelectionClient.isEmpty()) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		BlockHitResult hit = RtsSelectionClient.pickBlock(x, y);
		if (hit == null) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.no_target"));
			return;
		}
		RtsOrderKind kind = RtsClient.placeOrderKey.isDown() ? RtsOrderKind.PLACE
				: RtsClient.mineOrderKey.isDown() ? RtsOrderKind.MINE
				: RtsOrderKind.MOVE;
		int slot = client.player.getInventory().getSelectedSlot();
		if (ClientPlayNetworking.canSend(RtsOrderPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsOrderPayload(RtsSelectionClient.selectedIds(), kind, hit.getBlockPos(),
					hit.getDirection(), slot, shift));
		}
	}
}
