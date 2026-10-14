package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.network.RtsContentsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsDepositPayload;
import io.github.oshogun.rabodepalha.network.RtsDigPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsRemoveTaskPayload;
import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsWithdrawPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;

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
		if (ClientPlayNetworking.canSend(RtsOrderPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsOrderPayload(RtsSelectionClient.selectedIds(), kind,
					hit.getBlockPos(), hit.getDirection(), RtsItemPickClient.picked(), shift));
		}
	}

	/** Clears the selected workers' queues and current tasks, like {@code /rdp clear}. */
	public static void sendStop() {
		sendStop(RtsSelectionClient.selectedIds());
	}

	/** Clears the queues and current tasks of {@code workerIds}; the panel's Clear button. */
	public static void sendStop(List<Integer> workerIds) {
		if (workerIds.isEmpty()) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsStopPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsStopPayload(workerIds));
		}
	}

	/** Removes queued task {@code taskId} from worker {@code workerId}; the panel's Remove button. */
	public static void sendRemoveTask(int workerId, long taskId) {
		if (ClientPlayNetworking.canSend(RtsRemoveTaskPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsRemoveTaskPayload(workerId, taskId));
		}
	}

	/**
	 * Digs {@code area} with the selected workers, like {@code /rdp dig <from> <to> [workers]}. The caller has
	 * checked {@link #digProblem}; the server repeats every check.
	 */
	public static void sendDig(RtsDigArea area, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsDigPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsDigPayload(RtsSelectionClient.selectedIds(),
					new BlockPos(area.minX(), area.minY(), area.minZ()),
					new BlockPos(area.maxX(), area.maxY(), area.maxZ()), shift));
		}
	}

	/**
	 * Why the server would reject digging {@code area}, or {@code null} when the client sees no problem. The
	 * checks follow the server's order, and the volume is checked before the per-chunk loop.
	 */
	public static @Nullable Component digProblem(RtsDigArea area) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return Component.translatable("rabodepalha.rts.order.not_loaded");
		}
		if (level.isOutsideBuildHeight(area.minY()) || level.isOutsideBuildHeight(area.maxY())) {
			return Component.translatable("rabodepalha.rts.order.area_out_of_world");
		}
		if (area.exceeds(DigAreaTask.MAX_VOLUME)) {
			return Component.translatable("rabodepalha.rts.order.area_too_large");
		}
		Vec3 eye = player.getEyePosition();
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z)
				> RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE) {
			return Component.translatable("rabodepalha.rts.order.target_too_far");
		}
		for (int cx = SectionPos.blockToSectionCoord(area.minX()); cx <= SectionPos.blockToSectionCoord(area.maxX()); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(area.minZ()); cz <= SectionPos.blockToSectionCoord(area.maxZ());
					cz++) {
				if (!level.hasChunk(cx, cz)) {
					return Component.translatable("rabodepalha.rts.order.not_loaded");
				}
			}
		}
		return null;
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

	/** Deposits into the hovered container; the caller has checked the deposit hint. */
	public static void sendDeposit(RtsSelectionClient.Hover hover, boolean shift) {
		BlockHitResult hit = hover.block();
		if (hit != null) {
			sendDeposit(hit.getBlockPos(), shift);
		}
	}

	/**
	 * Puts the selected workers' items into the container at {@code pos}, like {@code /rdp deposit <pos> [item]}:
	 * only the picked item when one is picked, everything otherwise.
	 */
	public static void sendDeposit(BlockPos pos, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsDepositPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsDepositPayload(RtsSelectionClient.selectedIds(), pos,
					RtsItemPickClient.picked(), shift));
		}
	}

	/** Asks for the contents of the hovered container; the caller has checked the withdraw hint. */
	public static void requestContents(RtsSelectionClient.Hover hover) {
		BlockHitResult hit = hover.block();
		if (hit != null) {
			requestContents(hit.getBlockPos());
		}
	}

	/**
	 * Asks the server for the contents of the container at {@code pos}; the reply opens the withdraw view. The
	 * server repeats every check.
	 */
	public static void requestContents(BlockPos pos) {
		RtsContainerViewClient.request(pos);
		if (ClientPlayNetworking.canSend(RtsContentsRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsContentsRequestPayload(pos));
		}
	}

	/**
	 * Withdraws {@code count} of {@code item} from the container at {@code pos} with each selected worker, like
	 * {@code /rdp withdraw <pos> <item> [count]}. The count is per worker.
	 */
	public static void sendWithdraw(BlockPos pos, Identifier item, int count, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsWithdrawPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsWithdrawPayload(RtsSelectionClient.selectedIds(), pos, item, count, shift));
		}
	}
}
