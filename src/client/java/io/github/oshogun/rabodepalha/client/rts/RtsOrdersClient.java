package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBuildPayload;
import io.github.oshogun.rabodepalha.network.RtsContentsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsDepositPayload;
import io.github.oshogun.rabodepalha.network.RtsDigPayload;
import io.github.oshogun.rabodepalha.network.RtsHarvestPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsPlantPayload;
import io.github.oshogun.rabodepalha.network.RtsRemoveTaskPayload;
import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsTendPayload;
import io.github.oshogun.rabodepalha.network.RtsWithdrawPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks;

/**
 * Client side of RTS orders. An order sends the clicked block and face for the selected workers; the server
 * derives the target, checks the order and answers through the HUD. A plain right-click is a move, or a use on a lever or
 * button; other kinds come from the command bar. Shift appends to each worker's queue instead of replacing it. Orders that
 * {@link RtsOrderHint} already knows will be rejected are refused locally with the same reason the HUD hint
 * shows.
 */
public final class RtsOrdersClient {
	private RtsOrdersClient() {
	}

	/** A plain right-click: use the clicked lever or button, or move the selected workers to the clicked block. */
	public static void onRightClick(double x, double y, boolean shift) {
		// The order targets the block the ray hits; a worker under the cursor only keeps it a move.
		RtsSelectionClient.Hover hover = RtsSelectionClient.hoverAt(x, y);
		RtsOrderKind kind = RtsOrderHintClient.rightClickKind(hover);
		sendOrder(kind, hover, RtsOrderHintClient.current(hover, kind), shift);
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

	/**
	 * Tills and plants the field between the corners of {@code area} with the seed picked in the panel and the
	 * selected workers, like {@code /rdp plant <from> <to> <seed> [workers]}. The caller has checked
	 * {@link #plantProblem}; the server repeats every check.
	 */
	public static void sendPlant(RtsDigArea area, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		Optional<Identifier> seed = RtsItemPickClient.picked();
		if (seed.isEmpty()) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.not_a_seed"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsPlantPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsPlantPayload(RtsSelectionClient.selectedIds(),
					new BlockPos(area.minX(), area.minY(), area.minZ()),
					new BlockPos(area.maxX(), area.maxY(), area.maxZ()), seed.get(), shift));
		}
	}

	/**
	 * Why the server would reject planting the field between the corners of {@code area} with the picked seed, or
	 * {@code null} when the client sees no problem. The checks follow the server's order, and the field's size is
	 * checked before the per-chunk loop.
	 */
	public static @Nullable Component plantProblem(RtsDigArea area) {
		return fieldProblem(area, true);
	}

	/**
	 * Harvests the ripe plants in the field between the corners of {@code area} with the selected workers, like
	 * {@code /rdp harvest <from> <to> [workers]}. The caller has checked {@link #harvestProblem}; the server
	 * repeats every check.
	 */
	public static void sendHarvest(RtsDigArea area, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsHarvestPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsHarvestPayload(RtsSelectionClient.selectedIds(),
					new BlockPos(area.minX(), area.minY(), area.minZ()),
					new BlockPos(area.maxX(), area.maxY(), area.maxZ()), shift));
		}
	}

	/**
	 * Why the server would reject harvesting the field between the corners of {@code area}, or {@code null} when
	 * the client sees no problem. Same checks as {@link #plantProblem} without the seed.
	 */
	public static @Nullable Component harvestProblem(RtsDigArea area) {
		return fieldProblem(area, false);
	}

	/**
	 * Tends the field between the corners of {@code area} until stopped with the selected workers, putting the
	 * produce in {@code container} when present, like {@code /rdp tend <from> <to> [container] [workers]}. The caller
	 * has checked {@link #tendProblem}; the server repeats every check.
	 */
	public static void sendTend(RtsDigArea area, Optional<BlockPos> container, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		if (ClientPlayNetworking.canSend(RtsTendPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsTendPayload(RtsSelectionClient.selectedIds(),
					new BlockPos(area.minX(), area.minY(), area.minZ()),
					new BlockPos(area.maxX(), area.maxY(), area.maxZ()), container, shift));
		}
	}

	/** Why the server would reject tending the field between the corners of {@code area}; the same as Harvest. */
	public static @Nullable Component tendProblem(RtsDigArea area) {
		return fieldProblem(area, false);
	}

	/** The field checks shared by Plant, Harvest and Tend, in the server's order; the seed is checked only for Plant. */
	private static @Nullable Component fieldProblem(RtsDigArea area, boolean needsSeed) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return Component.translatable("rabodepalha.rts.order.not_loaded");
		}
		RtsField field = RtsField.of(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
		if (level.isOutsideBuildHeight(field.bottomY()) || level.isOutsideBuildHeight(field.topY())) {
			return Component.translatable("rabodepalha.rts.order.area_out_of_world");
		}
		if (field.exceeds()) {
			return Component.translatable("rabodepalha.rts.order.area_too_large");
		}
		if (needsSeed && !RtsItemPickClient.picked().map(FieldBlocks::isSeed).orElse(false)) {
			return Component.translatable("rabodepalha.rts.order.not_a_seed");
		}
		Vec3 eye = player.getEyePosition();
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z)
				> RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE) {
			return Component.translatable("rabodepalha.rts.order.target_too_far");
		}
		for (int cx = SectionPos.blockToSectionCoord(field.minX()); cx <= SectionPos.blockToSectionCoord(field.maxX());
				cx++) {
			for (int cz = SectionPos.blockToSectionCoord(field.minZ()); cz <= SectionPos.blockToSectionCoord(field.maxZ());
					cz++) {
				if (!level.hasChunk(cx, cz)) {
					return Component.translatable("rabodepalha.rts.order.not_loaded");
				}
			}
		}
		return null;
	}

	/** Asks the server for the blueprint list; the reply fills the picker. */
	public static void requestBlueprints(boolean includeVanilla) {
		if (ClientPlayNetworking.canSend(RtsBlueprintsRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsBlueprintsRequestPayload(includeVanilla));
		}
	}

	/** Asks the server for one blueprint's size and materials. */
	public static void requestBlueprintInfo(Identifier id) {
		if (ClientPlayNetworking.canSend(RtsBlueprintInfoRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsBlueprintInfoRequestPayload(id));
		}
	}

	/** The block a build preview is anchored to: the one next to the clicked face. */
	public static BlockPos anchor(BlockHitResult hit) {
		return hit.getBlockPos().relative(hit.getDirection());
	}

	/**
	 * The origin to send for the armed blueprint at {@code hover}, chosen so the turned box's minimum X/Z corner
	 * sits on the anchor block; null without a hovered block or before the blueprint's info arrives.
	 */
	public static @Nullable BlockPos buildOrigin(RtsSelectionClient.Hover hover) {
		BlockHitResult hit = hover.block();
		RtsBlueprintsClient.Info info = RtsBlueprintsClient.armedInfo();
		if (hit == null || info == null) {
			return null;
		}
		return BlueprintFootprint.originFor(anchor(hit), info.size(), RtsBlueprintsClient.rotation());
	}

	/**
	 * Builds the armed blueprint at {@code hover} with the selected workers, like
	 * {@code /rdp build <origin> <blueprint> [rotation]}. The caller has checked {@link #buildProblem}; the server
	 * repeats every check.
	 */
	public static void sendBuild(RtsSelectionClient.Hover hover, boolean shift) {
		if (RtsSelectionClient.count() <= 0) {
			RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.order.nothing_selected"));
			return;
		}
		BlockPos origin = buildOrigin(hover);
		Optional<Identifier> blueprint = RtsBlueprintsClient.armed();
		if (origin == null || blueprint.isEmpty()) {
			return;
		}
		if (ClientPlayNetworking.canSend(RtsBuildPayload.TYPE)) {
			ClientPlayNetworking.send(new RtsBuildPayload(RtsSelectionClient.selectedIds(), origin, blueprint.get(),
					RtsBlueprintsClient.rotation(), shift));
		}
	}

	/**
	 * Why the server would reject building over {@code box}, or {@code null} when the client sees no problem. The
	 * checks follow the server's order.
	 */
	public static @Nullable Component buildProblem(BoundingBox box) {
		String key = buildProblemKey(box);
		return key == null ? null : Component.translatable(key);
	}

	/** The translation key of {@link #buildProblem}, or {@code null} when the client sees no problem. */
	public static @Nullable String buildProblemKey(BoundingBox box) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return "rabodepalha.rts.order.not_loaded";
		}
		if (level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())) {
			return "rabodepalha.rts.order.area_out_of_world";
		}
		Vec3 eye = player.getEyePosition();
		RtsDigArea area = new RtsDigArea(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z)
				> RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE) {
			return "rabodepalha.rts.order.target_too_far";
		}
		for (int cx = SectionPos.blockToSectionCoord(box.minX()); cx <= SectionPos.blockToSectionCoord(box.maxX()); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(box.minZ()); cz <= SectionPos.blockToSectionCoord(box.maxZ());
					cz++) {
				if (!level.hasChunk(cx, cz)) {
					return "rabodepalha.rts.order.not_loaded";
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
