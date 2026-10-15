package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBuildPayload;
import io.github.oshogun.rabodepalha.network.RtsContentsPayload;
import io.github.oshogun.rabodepalha.network.RtsContentsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsDepositPayload;
import io.github.oshogun.rabodepalha.network.RtsDigPayload;
import io.github.oshogun.rabodepalha.network.RtsHarvestPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderFeedbackPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsPlantPayload;
import io.github.oshogun.rabodepalha.network.RtsRemoveTaskPayload;
import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.network.RtsWatchPayload;
import io.github.oshogun.rabodepalha.network.RtsWithdrawPayload;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.Usables;
import io.github.oshogun.rabodepalha.worker.WorkerSpawner;
import io.github.oshogun.rabodepalha.worker.task.BuildTask;
import io.github.oshogun.rabodepalha.worker.task.Containers;
import io.github.oshogun.rabodepalha.worker.task.DepositTask;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks;
import io.github.oshogun.rabodepalha.worker.task.HarvestAreaTask;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.PlantFieldTask;
import io.github.oshogun.rabodepalha.worker.task.UseTask;
import io.github.oshogun.rabodepalha.worker.task.WithdrawTask;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of RTS orders: validates an {@link RtsOrderPayload} and enqueues the matching task on each
 * valid worker. Global problems (permission, not in RTS mode, bad target, bad hotbar slot) reject the
 * whole order; per-worker problems (unknown, too far) reject only that worker, and the feedback says so.
 */
public final class RtsOrders {
	public static final int MAX_IDS = RtsOrderPayload.MAX_IDS;
	/** From the player's eyes to the target; matches the client's pick range. */
	public static final double MAX_TARGET_DISTANCE = 128.0;
	/** From a worker to the target. Only bounds the walk; reach is up to the task. */
	public static final double MAX_WORKER_DISTANCE = 128.0;
	/** The largest per-worker withdraw count; matches the withdraw task codec's range. */
	public static final int MAX_WITHDRAW_COUNT = Integer.MAX_VALUE;

	private RtsOrders() {
	}

	/**
	 * @param ok whether the whole order was applied as asked
	 * @param applied how many workers got the task
	 */
	public record Result(boolean ok, int applied, Component message) {
	}

	/** Entry point for the client's order. */
	public static Result handle(ServerPlayer player, RtsOrderPayload payload) {
		return handle(player, payload, player.permissions());
	}

	/** Validates and applies {@code payload}, then sends the feedback to the player if they can receive it. */
	public static Result handle(ServerPlayer player, RtsOrderPayload payload, PermissionSet permissions) {
		return handle(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handle(ServerPlayer, RtsOrderPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handle(ServerPlayer player, RtsOrderPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, apply(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's stop order. */
	public static Result handleStop(ServerPlayer player, RtsStopPayload payload) {
		return handleStop(player, payload, player.permissions());
	}

	/**
	 * Clears the queues and current tasks of the given workers, like {@code /rdp clear}, then sends the feedback.
	 * Any selected worker that is loaded and alive can be stopped, with no distance limit. Workers in unloaded
	 * chunks do not resolve by id and count as unknown.
	 */
	public static Result handleStop(ServerPlayer player, RtsStopPayload payload, PermissionSet permissions) {
		return sendFeedback(player, applyStop(player, payload, permissions));
	}

	/** Entry point for the client's dig order. */
	public static Result handleDig(ServerPlayer player, RtsDigPayload payload) {
		return handleDig(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link DigAreaTask} over the payload's cuboid, like {@code /rdp dig <from> <to>
	 * [workers]}, then sends the feedback. The whole order is rejected when the area is outside the world, over
	 * {@link DigAreaTask#MAX_VOLUME} blocks, too far from the player's eyes or not loaded.
	 */
	public static Result handleDig(ServerPlayer player, RtsDigPayload payload, PermissionSet permissions) {
		return handleDig(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handleDig(ServerPlayer, RtsDigPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handleDig(ServerPlayer player, RtsDigPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyDig(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's plant order. */
	public static Result handlePlant(ServerPlayer player, RtsPlantPayload payload) {
		return handlePlant(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link PlantFieldTask} over the payload's field, like {@code /rdp plant <from>
	 * <to> <seed> [workers]}, then sends the feedback. The whole order is rejected when the field's window is
	 * outside the world, the field is over {@link RtsField#MAX_COLUMNS} columns or {@link RtsField#MAX_RISE} rise,
	 * the item is not a seed, or the field is too far from the player's eyes or not loaded.
	 */
	public static Result handlePlant(ServerPlayer player, RtsPlantPayload payload, PermissionSet permissions) {
		return handlePlant(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handlePlant(ServerPlayer, RtsPlantPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handlePlant(ServerPlayer player, RtsPlantPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyPlant(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's harvest order. */
	public static Result handleHarvest(ServerPlayer player, RtsHarvestPayload payload) {
		return handleHarvest(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link HarvestAreaTask} over the payload's field, like {@code /rdp harvest <from>
	 * <to> [workers]}, then sends the feedback. The whole order is rejected when the field's window is outside the
	 * world, the field is over {@link RtsField#MAX_COLUMNS} columns or {@link RtsField#MAX_RISE} rise, or the field
	 * is too far from the player's eyes or not loaded.
	 */
	public static Result handleHarvest(ServerPlayer player, RtsHarvestPayload payload, PermissionSet permissions) {
		return handleHarvest(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handleHarvest(ServerPlayer, RtsHarvestPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handleHarvest(ServerPlayer player, RtsHarvestPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyHarvest(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's spawn order. */
	public static Result handleSpawn(ServerPlayer player, RtsSpawnPayload payload) {
		return handleSpawn(player, payload, player.permissions());
	}

	/** Spawns a worker commanded by the player on top of the clicked block, then sends the feedback. */
	public static Result handleSpawn(ServerPlayer player, RtsSpawnPayload payload, PermissionSet permissions) {
		return handleSpawn(player, payload, permissions, MAX_TARGET_DISTANCE);
	}

	/** As {@link #handleSpawn(ServerPlayer, RtsSpawnPayload, PermissionSet)}, with an explicit distance limit (for tests). */
	public static Result handleSpawn(ServerPlayer player, RtsSpawnPayload payload, PermissionSet permissions,
			double maxTargetDistance) {
		return sendFeedback(player, applySpawn(player, payload, permissions, maxTargetDistance));
	}

	/** Entry point for the client's watch request. */
	public static Result handleWatch(ServerPlayer player, RtsWatchPayload payload) {
		return handleWatch(player, payload, player.permissions());
	}

	/**
	 * Replaces the workers the player's RTS panel watches; an empty list ends the watch. When some ids are live
	 * workers, the others are reported as gone in the next snapshot. When none is, the watch is dropped and
	 * rejected as unknown workers. Sends no feedback; the snapshots are the answer.
	 */
	public static Result handleWatch(ServerPlayer player, RtsWatchPayload payload, PermissionSet permissions) {
		return applyWatch(player, payload, permissions);
	}

	/** Entry point for the client's remove-task order. */
	public static Result handleRemoveTask(ServerPlayer player, RtsRemoveTaskPayload payload) {
		return handleRemoveTask(player, payload, player.permissions());
	}

	/** Drops one queued task of a worker, by its runtime id, then sends the feedback. */
	public static Result handleRemoveTask(ServerPlayer player, RtsRemoveTaskPayload payload, PermissionSet permissions) {
		return sendFeedback(player, applyRemoveTask(player, payload, permissions));
	}

	/** Entry point for the client's deposit order. */
	public static Result handleDeposit(ServerPlayer player, RtsDepositPayload payload) {
		return handleDeposit(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link DepositTask} on the clicked container, like {@code /rdp deposit <pos>
	 * [item]}: everything, or only the picked item. Then sends the feedback.
	 */
	public static Result handleDeposit(ServerPlayer player, RtsDepositPayload payload, PermissionSet permissions) {
		return handleDeposit(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handleDeposit(ServerPlayer, RtsDepositPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handleDeposit(ServerPlayer player, RtsDepositPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyDeposit(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's withdraw order. */
	public static Result handleWithdraw(ServerPlayer player, RtsWithdrawPayload payload) {
		return handleWithdraw(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link WithdrawTask} of the payload's item and count on the clicked container,
	 * like {@code /rdp withdraw <pos> <item> [count]}. The count is per worker. Then sends the feedback.
	 */
	public static Result handleWithdraw(ServerPlayer player, RtsWithdrawPayload payload, PermissionSet permissions) {
		return handleWithdraw(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handleWithdraw(ServerPlayer, RtsWithdrawPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handleWithdraw(ServerPlayer player, RtsWithdrawPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyWithdraw(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	/** Entry point for the client's contents request. */
	public static ContentsResult handleContents(ServerPlayer player, RtsContentsRequestPayload payload) {
		return handleContents(player, payload, player.permissions());
	}

	/**
	 * Answers with a read-only snapshot of the clicked container when the request passes the order checks, or with
	 * the feedback only on rejection.
	 */
	public static ContentsResult handleContents(ServerPlayer player, RtsContentsRequestPayload payload,
			PermissionSet permissions) {
		return handleContents(player, payload, permissions, MAX_TARGET_DISTANCE);
	}

	/** As {@link #handleContents(ServerPlayer, RtsContentsRequestPayload, PermissionSet)}, with an explicit distance limit (for tests). */
	public static ContentsResult handleContents(ServerPlayer player, RtsContentsRequestPayload payload,
			PermissionSet permissions, double maxTargetDistance) {
		ContentsResult contents = contents(player, payload, permissions, maxTargetDistance);
		if (!contents.result().ok()) {
			sendFeedback(player, contents.result());
		} else if (ServerPlayNetworking.canSend(player, RtsContentsPayload.TYPE)) {
			ServerPlayNetworking.send(player, new RtsContentsPayload(payload.pos(), contents.entries()));
		}
		return contents;
	}

	/** A contents request's outcome and, when it is ok, the container's items summed by id. */
	public record ContentsResult(Result result, List<RtsItemPicker.Entry> entries) {
		public ContentsResult {
			entries = List.copyOf(entries);
		}
	}

	/** Checks a contents request and summarizes the container; sends nothing. */
	public static ContentsResult contents(ServerPlayer player, RtsContentsRequestPayload payload,
			PermissionSet permissions, double maxTargetDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return new ContentsResult(playerProblem, List.of());
		}
		ServerLevel level = player.level();
		BlockPos pos = payload.pos().immutable();
		Result targetProblem = checkTarget(player, level, pos, maxTargetDistance);
		if (targetProblem != null) {
			return new ContentsResult(targetProblem, List.of());
		}
		Container container = Containers.find(level, pos);
		if (container == null) {
			return new ContentsResult(reject("rabodepalha.rts.order.not_container"), List.of());
		}
		List<RtsItemPicker.Entry> entries = Containers.summarize(container);
		return new ContentsResult(new Result(true, 0, Component.empty()), entries);
	}

	/** Entry point for the client's blueprint list request. */
	public static BlueprintsResult handleBlueprints(ServerPlayer player, RtsBlueprintsRequestPayload payload) {
		return handleBlueprints(player, payload, player.permissions());
	}

	/**
	 * Answers with the blueprint ids and sources of {@link BlueprintCatalog} when the player may give orders, or
	 * with the feedback only on rejection. Loads no template.
	 */
	public static BlueprintsResult handleBlueprints(ServerPlayer player, RtsBlueprintsRequestPayload payload,
			PermissionSet permissions) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			sendFeedback(player, playerProblem);
			return new BlueprintsResult(playerProblem, new BlueprintCatalog.Listing(List.of(), 0));
		}
		BlueprintCatalog.Listing listing = BlueprintCatalog.list(player.level().getServer(), payload.includeVanilla());
		if (ServerPlayNetworking.canSend(player, RtsBlueprintsPayload.TYPE)) {
			ServerPlayNetworking.send(player, RtsBlueprintsPayload.of(listing, payload.includeVanilla()));
		}
		return new BlueprintsResult(new Result(true, 0, Component.empty()), listing);
	}

	/** A blueprint list request's outcome and, when it is ok, the listing sent. */
	public record BlueprintsResult(Result result, BlueprintCatalog.Listing listing) {
	}

	/** Entry point for the client's blueprint info request. */
	public static BlueprintInfoResult handleBlueprintInfo(ServerPlayer player, RtsBlueprintInfoRequestPayload payload) {
		return handleBlueprintInfo(player, payload, player.permissions());
	}

	/**
	 * Answers with one blueprint's size and materials when the player may give orders and the blueprint exists,
	 * or with the feedback only on rejection.
	 */
	public static BlueprintInfoResult handleBlueprintInfo(ServerPlayer player, RtsBlueprintInfoRequestPayload payload,
			PermissionSet permissions) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			sendFeedback(player, playerProblem);
			return new BlueprintInfoResult(playerProblem, Optional.empty());
		}
		Optional<RtsBlueprintInfoPayload> info = blueprintInfo(player.level().getServer(), payload.id());
		if (info.isEmpty()) {
			Result unknown = reject("rabodepalha.rts.order.unknown_blueprint");
			sendFeedback(player, unknown);
			return new BlueprintInfoResult(unknown, Optional.empty());
		}
		if (ServerPlayNetworking.canSend(player, RtsBlueprintInfoPayload.TYPE)) {
			ServerPlayNetworking.send(player, info.get());
		}
		return new BlueprintInfoResult(new Result(true, 0, Component.empty()), info);
	}

	/** A blueprint info request's outcome and, when it is ok, the info sent. */
	public record BlueprintInfoResult(Result result, Optional<RtsBlueprintInfoPayload> info) {
	}

	/**
	 * The size and materials of blueprint {@code id}, most needed first and capped at
	 * {@link RtsBlueprintInfoPayload#MAX_MATERIALS}; empty for unknown ids. Loads the template.
	 */
	public static Optional<RtsBlueprintInfoPayload> blueprintInfo(MinecraftServer server, Identifier id) {
		Optional<StructureTemplate> template = server.getStructureTemplateManager().get(id);
		if (template.isEmpty()) {
			return Optional.empty();
		}
		List<RtsBlueprintInfoPayload.Material> materials = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : BuildTask.materials(template.get()).entrySet()) {
			materials.add(new RtsBlueprintInfoPayload.Material(entry.getKey(), entry.getValue()));
		}
		materials.sort(Comparator.comparingInt(RtsBlueprintInfoPayload.Material::count).reversed()
				.thenComparing(material -> BuiltInRegistries.ITEM.getKey(material.item())));
		List<RtsBlueprintInfoPayload.Material> capped =
				materials.subList(0, Math.min(materials.size(), RtsBlueprintInfoPayload.MAX_MATERIALS));
		return Optional.of(new RtsBlueprintInfoPayload(id, template.get().getSize(), capped));
	}

	/** Entry point for the client's build order. */
	public static Result handleBuild(ServerPlayer player, RtsBuildPayload payload) {
		return handleBuild(player, payload, player.permissions());
	}

	/**
	 * Gives each selected worker a {@link BuildTask} of the payload's blueprint at its origin and rotation, like
	 * {@code /rdp build <origin> <blueprint> [rotation]}, then sends the feedback. The origin is the template's
	 * (0,0,0) corner, used verbatim. The whole order is rejected when the blueprint is unknown, or its footprint is
	 * outside the world, too far from the player's eyes or not loaded.
	 */
	public static Result handleBuild(ServerPlayer player, RtsBuildPayload payload, PermissionSet permissions) {
		return handleBuild(player, payload, permissions, MAX_TARGET_DISTANCE, MAX_WORKER_DISTANCE);
	}

	/** As {@link #handleBuild(ServerPlayer, RtsBuildPayload, PermissionSet)}, with explicit distance limits (for tests). */
	public static Result handleBuild(ServerPlayer player, RtsBuildPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		return sendFeedback(player, applyBuild(player, payload, permissions, maxTargetDistance, maxWorkerDistance));
	}

	private static Result sendFeedback(ServerPlayer player, Result result) {
		if (ServerPlayNetworking.canSend(player, RtsOrderFeedbackPayload.TYPE)) {
			ServerPlayNetworking.send(player, new RtsOrderFeedbackPayload(result.ok(), result.message()));
		}
		return result;
	}

	/** Where the workers act: on top of the clicked block, the block itself, or against the clicked face. */
	public static BlockPos target(RtsOrderKind kind, BlockPos clicked, Direction face) {
		return switch (kind) {
			case MOVE -> clicked.above();
			case MINE, USE -> clicked.immutable();
			case PLACE -> clicked.relative(face);
		};
	}

	private static Result apply(ServerPlayer player, RtsOrderPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		RtsOrderKind kind = payload.kind();
		BlockPos target = target(kind, payload.clicked(), payload.face());
		Result targetProblem = checkTarget(player, level, target, maxTargetDistance);
		if (targetProblem != null) {
			return targetProblem;
		}

		BlockState placeState = null;
		if (kind == RtsOrderKind.MINE && level.getBlockState(target).isAir()) {
			return reject("rabodepalha.rts.order.air");
		}
		if (kind == RtsOrderKind.USE && !Usables.isUsable(level.getBlockState(target))) {
			return reject("rabodepalha.rts.order.not_usable");
		}
		if (kind == RtsOrderKind.PLACE) {
			// The block comes from the item picked in the workers' inventory; a worker without it fails in PlaceTask.
			Item item = payload.item().flatMap(BuiltInRegistries.ITEM::getOptional).orElse(null);
			if (!(item instanceof BlockItem blockItem)) {
				return reject("rabodepalha.rts.order.no_block");
			}
			placeState = blockItem.getBlock().defaultBlockState();
		}

		Workers workers = resolveWorkers(level, ids, Vec3.atBottomCenterOf(target), maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		BlockState state = placeState;
		// A door is used through its lower half, so a click on either half queues the same task.
		BlockPos taskPos = kind == RtsOrderKind.USE ? Usables.canonicalPos(level, target) : target;
		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.queued" : "rabodepalha.rts.order.replaced",
				kind.name().toLowerCase(Locale.ROOT), target.getX(), target.getY(), target.getZ(), workers.valid().size());
		return assign(player, workers, payload.append(), () -> newTask(kind, taskPos, state), applied);
	}

	private static Result applyDig(ServerPlayer player, RtsDigPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		BlockPos from = payload.from();
		BlockPos to = payload.to();
		RtsDigArea area = RtsDigArea.between(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		if (level.isOutsideBuildHeight(area.minY()) || level.isOutsideBuildHeight(area.maxY())) {
			return reject("rabodepalha.rts.order.area_out_of_world");
		}
		// Before any per-chunk work, so a crafted payload cannot make the server scan a huge footprint.
		if (area.exceeds(DigAreaTask.MAX_VOLUME)) {
			return reject("rabodepalha.rts.order.area_too_large");
		}
		Vec3 eye = player.getEyePosition();
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z) > maxTargetDistance * maxTargetDistance) {
			return reject("rabodepalha.rts.order.target_too_far");
		}
		if (!level.hasChunksAt(area.minX(), area.minZ(), area.maxX(), area.maxZ())) {
			return reject("rabodepalha.rts.order.not_loaded");
		}

		Workers workers = resolveWorkers(level, ids, new Vec3(area.centerX(), area.centerY(), area.centerZ()),
				maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		BlockPos min = new BlockPos(area.minX(), area.minY(), area.minZ());
		BlockPos max = new BlockPos(area.maxX(), area.maxY(), area.maxZ());
		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.dig_queued" : "rabodepalha.rts.order.dig_replaced",
				area.sizeX(), area.sizeZ(), area.sizeY(), workers.valid().size());
		return assign(player, workers, payload.append(), () -> new DigAreaTask(min, max), applied);
	}

	private static Result applyPlant(ServerPlayer player, RtsPlantPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		BlockPos from = payload.from().immutable();
		BlockPos to = payload.to().immutable();
		RtsField field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		Result boundsProblem = checkFieldBounds(level, field);
		if (boundsProblem != null) {
			return boundsProblem;
		}
		if (!FieldBlocks.isSeed(payload.seed())) {
			return reject("rabodepalha.rts.order.not_a_seed");
		}
		// The picked corners, which is what the client's pick range checks.
		RtsDigArea area = RtsDigArea.between(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		Result reachProblem = checkFieldReach(player, level, field, area, maxTargetDistance);
		if (reachProblem != null) {
			return reachProblem;
		}

		Workers workers = resolveWorkers(level, ids, new Vec3(area.centerX(), area.centerY(), area.centerZ()),
				maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		Item seed = BuiltInRegistries.ITEM.getValue(payload.seed());
		// Args: seed name, size x, size z, worker count.
		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.plant_queued" : "rabodepalha.rts.order.plant_replaced",
				Component.translatable(seed.getDescriptionId()), field.sizeX(), field.sizeZ(), workers.valid().size());
		return assign(player, workers, payload.append(), () -> new PlantFieldTask(from, to, seed), applied);
	}

	private static Result applyHarvest(ServerPlayer player, RtsHarvestPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		BlockPos from = payload.from().immutable();
		BlockPos to = payload.to().immutable();
		RtsField field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		Result boundsProblem = checkFieldBounds(level, field);
		if (boundsProblem != null) {
			return boundsProblem;
		}
		RtsDigArea area = RtsDigArea.between(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		Result reachProblem = checkFieldReach(player, level, field, area, maxTargetDistance);
		if (reachProblem != null) {
			return reachProblem;
		}

		Workers workers = resolveWorkers(level, ids, new Vec3(area.centerX(), area.centerY(), area.centerZ()),
				maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		// Args: size x, size z, worker count.
		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.harvest_queued" : "rabodepalha.rts.order.harvest_replaced",
				field.sizeX(), field.sizeZ(), workers.valid().size());
		return assign(player, workers, payload.append(), () -> new HarvestAreaTask(from, to), applied);
	}

	/** Rejects fields whose window is outside the world or that are over the field cap; null when they fit. */
	private static @Nullable Result checkFieldBounds(ServerLevel level, RtsField field) {
		if (level.isOutsideBuildHeight(field.bottomY()) || level.isOutsideBuildHeight(field.topY())) {
			return reject("rabodepalha.rts.order.area_out_of_world");
		}
		// Before any per-chunk work, so a crafted payload cannot make the server scan a huge footprint.
		if (field.exceeds()) {
			return reject("rabodepalha.rts.order.area_too_large");
		}
		return null;
	}

	/**
	 * Rejects fields whose picked corners ({@code area}, which is what the client's pick range checks) are too far
	 * from the player's eyes, or whose columns are not loaded; null when the field is fine.
	 */
	private static @Nullable Result checkFieldReach(ServerPlayer player, ServerLevel level, RtsField field,
			RtsDigArea area, double maxTargetDistance) {
		Vec3 eye = player.getEyePosition();
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z) > maxTargetDistance * maxTargetDistance) {
			return reject("rabodepalha.rts.order.target_too_far");
		}
		if (!level.hasChunksAt(field.minX(), field.minZ(), field.maxX(), field.maxZ())) {
			return reject("rabodepalha.rts.order.not_loaded");
		}
		return null;
	}

	private static Result applyBuild(ServerPlayer player, RtsBuildPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		Identifier blueprint = payload.blueprint();
		Optional<StructureTemplate> template = level.getServer().getStructureTemplateManager().get(blueprint);
		if (template.isEmpty()) {
			return reject("rabodepalha.rts.order.unknown_blueprint");
		}
		BlockPos origin = payload.origin().immutable();
		Rotation rotation = payload.rotation();
		Vec3i size = template.get().getSize();
		BoundingBox box = BlueprintFootprint.box(origin, size, rotation);
		RtsDigArea area = RtsDigArea.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		if (level.isOutsideBuildHeight(area.minY()) || level.isOutsideBuildHeight(area.maxY())) {
			return reject("rabodepalha.rts.order.area_out_of_world");
		}
		Vec3 eye = player.getEyePosition();
		if (area.farthestCornerDistanceSqr(eye.x, eye.y, eye.z) > maxTargetDistance * maxTargetDistance) {
			return reject("rabodepalha.rts.order.target_too_far");
		}
		if (!level.hasChunksAt(area.minX(), area.minZ(), area.maxX(), area.maxZ())) {
			return reject("rabodepalha.rts.order.not_loaded");
		}

		Workers workers = resolveWorkers(level, ids, new Vec3(area.centerX(), area.centerY(), area.centerZ()),
				maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		// Args: blueprint id, origin x/y/z, rotation in degrees, worker count.
		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.build_queued" : "rabodepalha.rts.order.build_replaced",
				blueprint.toString(), origin.getX(), origin.getY(), origin.getZ(), degrees(rotation),
				workers.valid().size());
		return assign(player, workers, payload.append(), () -> new BuildTask(origin, blueprint, rotation), applied);
	}

	private static int degrees(Rotation rotation) {
		return switch (rotation) {
			case NONE -> 0;
			case CLOCKWISE_90 -> 90;
			case CLOCKWISE_180 -> 180;
			case COUNTERCLOCKWISE_90 -> 270;
		};
	}

	private static Result applyDeposit(ServerPlayer player, RtsDepositPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}
		ServerLevel level = player.level();
		BlockPos pos = payload.pos().immutable();
		Result containerProblem = checkContainer(player, level, pos, maxTargetDistance);
		if (containerProblem != null) {
			return containerProblem;
		}
		Optional<Identifier> item = payload.item();
		if (item.isPresent() && knownItem(item.get()) == null) {
			return reject("rabodepalha.rts.order.unknown_item");
		}

		Workers workers = resolveWorkers(level, ids, Vec3.atBottomCenterOf(pos), maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		Component what = item.map(id -> (Component) Component.translatable(knownItem(id).getDescriptionId()))
				.orElseGet(() -> Component.translatable("rabodepalha.rts.order.deposit_everything"));
		Component applied = Component.translatable("rabodepalha.rts.order.deposit_applied",
				what, pos.getX(), pos.getY(), pos.getZ(), workers.valid().size());
		return assign(player, workers, payload.append(), () -> new DepositTask(pos, item), applied);
	}

	private static Result applyWithdraw(ServerPlayer player, RtsWithdrawPayload payload, PermissionSet permissions,
			double maxTargetDistance, double maxWorkerDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}
		ServerLevel level = player.level();
		BlockPos pos = payload.pos().immutable();
		Result containerProblem = checkContainer(player, level, pos, maxTargetDistance);
		if (containerProblem != null) {
			return containerProblem;
		}
		int count = payload.count();
		if (count < 1 || count > MAX_WITHDRAW_COUNT) {
			return reject("rabodepalha.rts.order.bad_count");
		}
		Identifier itemId = payload.item();
		Item item = knownItem(itemId);
		if (item == null) {
			return reject("rabodepalha.rts.order.unknown_item");
		}

		Workers workers = resolveWorkers(level, ids, Vec3.atBottomCenterOf(pos), maxWorkerDistance);
		if (workers.valid().isEmpty()) {
			return workers.noneValid();
		}
		int size = workers.valid().size();
		Component applied = Component.translatable(
				size == 1 ? "rabodepalha.rts.order.withdraw_applied_one" : "rabodepalha.rts.order.withdraw_applied",
				count, Component.translatable(item.getDescriptionId()), size);
		return assign(player, workers, payload.append(), () -> new WithdrawTask(pos, itemId, count), applied);
	}

	/** The workers an order resolved to, and how many ids were rejected and why. */
	private record Workers(List<WorkerEntity> valid, int unknown, int tooFar) {
		/** The feedback when no worker is valid. */
		Result noneValid() {
			MutableComponent message = Component.empty();
			if (unknown > 0) {
				message.append(Component.translatable("rabodepalha.rts.order.unknown_worker", unknown));
			}
			if (tooFar > 0) {
				message.append(unknown > 0 ? ", " : "").append(Component.translatable("rabodepalha.rts.order.too_far", tooFar));
			}
			return new Result(false, 0, message);
		}
	}

	/** Resolves {@code ids} to live workers within {@code maxWorkerDistance} of {@code center}. */
	private static Workers resolveWorkers(ServerLevel level, List<Integer> ids, Vec3 center, double maxWorkerDistance) {
		// Duplicated ids would otherwise queue the task twice on the same worker.
		List<WorkerEntity> valid = new ArrayList<>();
		int unknown = 0;
		int tooFar = 0;
		for (int id : new LinkedHashSet<>(ids)) {
			if (!(level.getEntity(id) instanceof WorkerEntity worker) || !worker.isAlive()) {
				unknown++;
			} else if (worker.position().distanceToSqr(center) > maxWorkerDistance * maxWorkerDistance) {
				tooFar++;
			} else {
				valid.add(worker);
			}
		}
		return new Workers(valid, unknown, tooFar);
	}

	/**
	 * Gives each valid worker a fresh task from {@code task}, after the current ones with {@code append} or
	 * replacing them otherwise, and reports {@code applied}, wrapped as partial if some ids were rejected.
	 */
	private static Result assign(ServerPlayer player, Workers workers, boolean append,
			Supplier<Task<WorkerEntity>> task, Component applied) {
		List<WorkerEntity> valid = workers.valid();
		for (WorkerEntity worker : valid) {
			worker.addCommander(player.getUUID());
			if (!append) {
				worker.tasks().clear(worker, TaskOutcome.Cancelled.Cause.SUPERSEDED);
			}
			worker.tasks().enqueue(task.get());
		}

		int rejected = workers.unknown() + workers.tooFar();
		if (rejected == 0) {
			return new Result(true, valid.size(), applied);
		}
		return new Result(false, valid.size(), Component.translatable("rabodepalha.rts.order.partial",
				applied, rejected, workers.unknown(), workers.tooFar()));
	}

	private static Result applyStop(ServerPlayer player, RtsStopPayload payload, PermissionSet permissions) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.isEmpty() || ids.size() > MAX_IDS) {
			return reject("rabodepalha.rts.order.no_workers");
		}

		ServerLevel level = player.level();
		List<WorkerEntity> valid = new ArrayList<>();
		int unknown = 0;
		int notLoaded = 0;
		for (int id : new LinkedHashSet<>(ids)) {
			if (!(level.getEntity(id) instanceof WorkerEntity worker) || !worker.isAlive()) {
				unknown++;
			} else if (!level.isLoaded(worker.blockPosition())) {
				notLoaded++;
			} else {
				valid.add(worker);
			}
		}
		if (valid.isEmpty()) {
			MutableComponent message = Component.empty();
			String separator = "";
			if (unknown > 0) {
				message.append(Component.translatable("rabodepalha.rts.order.unknown_worker", unknown));
				separator = ", ";
			}
			if (notLoaded > 0) {
				message.append(separator).append(Component.translatable("rabodepalha.rts.order.worker_not_loaded", notLoaded));
			}
			return new Result(false, 0, message);
		}

		for (WorkerEntity worker : valid) {
			worker.tasks().clear(worker, TaskOutcome.Cancelled.Cause.CLEARED);
			RtsWatchers.markDirty(player, worker.getId());
		}
		Component stopped = Component.translatable("rabodepalha.rts.order.stopped", valid.size());
		int rejected = unknown + notLoaded;
		if (rejected == 0) {
			return new Result(true, valid.size(), stopped);
		}
		return new Result(false, valid.size(), Component.translatable("rabodepalha.rts.order.stop_partial",
				stopped, rejected, unknown, notLoaded));
	}

	private static Result applyWatch(ServerPlayer player, RtsWatchPayload payload, PermissionSet permissions) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		List<Integer> ids = payload.workerIds();
		if (ids.size() > RtsWatchers.MAX_WATCHED) {
			return reject("rabodepalha.rts.order.too_many_workers");
		}
		// A selected worker may die before the watch arrives; watch the rest and report it as gone.
		ServerLevel level = player.level();
		List<Integer> live = new ArrayList<>();
		List<Integer> gone = new ArrayList<>();
		for (int id : new LinkedHashSet<>(ids)) {
			if (level.getEntity(id) instanceof WorkerEntity worker && worker.isAlive()) {
				live.add(id);
			} else {
				gone.add(id);
			}
		}
		if (live.isEmpty() && !gone.isEmpty()) {
			// Nothing to watch; drop the previous watch so it stops sending stale workers.
			RtsWatchers.set(player, List.of(), List.of());
			return new Result(false, 0, Component.translatable("rabodepalha.rts.order.unknown_worker", gone.size()));
		}
		RtsWatchers.set(player, live, gone);
		return new Result(true, live.size(), Component.empty());
	}

	private static Result applyRemoveTask(ServerPlayer player, RtsRemoveTaskPayload payload, PermissionSet permissions) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		ServerLevel level = player.level();
		if (!(level.getEntity(payload.workerId()) instanceof WorkerEntity worker) || !worker.isAlive()) {
			return new Result(false, 0, Component.translatable("rabodepalha.rts.order.unknown_worker", 1));
		}
		if (!level.isLoaded(worker.blockPosition())) {
			return new Result(false, 0, Component.translatable("rabodepalha.rts.order.worker_not_loaded", 1));
		}
		if (!worker.tasks().removePending(payload.taskId())) {
			return reject("rabodepalha.rts.order.task_gone");
		}
		RtsWatchers.markDirty(player, worker.getId());
		return new Result(true, 1, Component.translatable("rabodepalha.rts.order.task_removed"));
	}

	private static Result applySpawn(ServerPlayer player, RtsSpawnPayload payload, PermissionSet permissions,
			double maxTargetDistance) {
		Result playerProblem = checkPlayer(player, permissions);
		if (playerProblem != null) {
			return playerProblem;
		}
		ServerLevel level = player.level();
		BlockPos spot = payload.clicked().above();
		Result targetProblem = checkTarget(player, level, spot, maxTargetDistance);
		if (targetProblem != null) {
			return targetProblem;
		}
		Vec3 feet = WorkerSpawner.feetOn(spot);
		if (!WorkerSpawner.hasRoom(level, feet)) {
			return reject("rabodepalha.rts.order.no_room");
		}
		WorkerEntity worker = WorkerSpawner.spawn(level, feet, player.getYRot(), player.getUUID());
		if (worker == null) {
			return reject("rabodepalha.rts.order.spawn_failed");
		}
		return new Result(true, 1,
				Component.translatable("rabodepalha.rts.order.spawned", spot.getX(), spot.getY(), spot.getZ()));
	}

	/** Rejects players who may not give orders or are not in RTS mode; null when they may. */
	private static @Nullable Result checkPlayer(ServerPlayer player, PermissionSet permissions) {
		if (!RtsMode.canEnter(permissions)) {
			return reject("rabodepalha.rts.order.no_permission");
		}
		if (!RtsMode.isActive(player)) {
			return reject("rabodepalha.rts.order.not_rts");
		}
		return null;
	}

	/** Rejects targets too far from the player's eyes or not loaded; null when the target is fine. */
	private static @Nullable Result checkTarget(ServerPlayer player, ServerLevel level, BlockPos target,
			double maxTargetDistance) {
		if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > maxTargetDistance * maxTargetDistance) {
			return reject("rabodepalha.rts.order.target_too_far");
		}
		if (!level.isLoaded(target)) {
			return reject("rabodepalha.rts.order.not_loaded");
		}
		return null;
	}

	/** Runs {@link #checkTarget} on {@code pos} and rejects blocks that are not a container; null when it is one. */
	private static @Nullable Result checkContainer(ServerPlayer player, ServerLevel level, BlockPos pos,
			double maxTargetDistance) {
		Result targetProblem = checkTarget(player, level, pos, maxTargetDistance);
		if (targetProblem != null) {
			return targetProblem;
		}
		if (Containers.find(level, pos) == null) {
			return reject("rabodepalha.rts.order.not_container");
		}
		return null;
	}

	/** The registered item with {@code id}, or null for unknown ids and air. */
	private static @Nullable Item knownItem(Identifier id) {
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
		return item == Items.AIR ? null : item;
	}

	private static Task<WorkerEntity> newTask(RtsOrderKind kind, BlockPos target, BlockState placeState) {
		return switch (kind) {
			case MOVE -> new MoveToTask(target);
			case MINE -> new MineTask(target);
			case PLACE -> new PlaceTask(target, placeState);
			case USE -> new UseTask(target);
		};
	}

	private static Result reject(String key) {
		return new Result(false, 0, Component.translatable(key));
	}
}
