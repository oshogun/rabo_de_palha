package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import io.github.oshogun.rabodepalha.network.RtsOrderFeedbackPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.WorkerSpawner;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
			case MINE -> clicked.immutable();
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
		if (kind == RtsOrderKind.PLACE) {
			int slot = payload.hotbarSlot();
			ItemStack stack = Inventory.isHotbarSlot(slot) ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
			if (!(stack.getItem() instanceof BlockItem blockItem)) {
				return reject("rabodepalha.rts.order.no_block");
			}
			placeState = blockItem.getBlock().defaultBlockState();
		}

		// Duplicated ids would otherwise queue the task twice on the same worker.
		List<WorkerEntity> valid = new ArrayList<>();
		int unknown = 0;
		int tooFar = 0;
		Vec3 targetCenter = Vec3.atBottomCenterOf(target);
		for (int id : new LinkedHashSet<>(ids)) {
			if (!(level.getEntity(id) instanceof WorkerEntity worker) || !worker.isAlive()) {
				unknown++;
			} else if (worker.position().distanceToSqr(targetCenter) > maxWorkerDistance * maxWorkerDistance) {
				tooFar++;
			} else {
				valid.add(worker);
			}
		}

		if (valid.isEmpty()) {
			MutableComponent message = Component.empty();
			if (unknown > 0) {
				message.append(Component.translatable("rabodepalha.rts.order.unknown_worker", unknown));
			}
			if (tooFar > 0) {
				message.append(unknown > 0 ? ", " : "").append(Component.translatable("rabodepalha.rts.order.too_far", tooFar));
			}
			return new Result(false, 0, message);
		}

		for (WorkerEntity worker : valid) {
			worker.addCommander(player.getUUID());
			if (!payload.append()) {
				worker.tasks().clear(worker, TaskOutcome.Cancelled.Cause.SUPERSEDED);
			}
			worker.tasks().enqueue(newTask(kind, target, placeState));
		}

		Component applied = Component.translatable(
				payload.append() ? "rabodepalha.rts.order.queued" : "rabodepalha.rts.order.replaced",
				kind.name().toLowerCase(Locale.ROOT), target.getX(), target.getY(), target.getZ(), valid.size());
		int rejected = unknown + tooFar;
		if (rejected == 0) {
			return new Result(true, valid.size(), applied);
		}
		return new Result(false, valid.size(),
				Component.translatable("rabodepalha.rts.order.partial", applied, rejected, unknown, tooFar));
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
		}
		Component stopped = Component.translatable("rabodepalha.rts.order.stopped", valid.size());
		int rejected = unknown + notLoaded;
		if (rejected == 0) {
			return new Result(true, valid.size(), stopped);
		}
		return new Result(false, valid.size(), Component.translatable("rabodepalha.rts.order.stop_partial",
				stopped, rejected, unknown, notLoaded));
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

	private static Task<WorkerEntity> newTask(RtsOrderKind kind, BlockPos target, BlockState placeState) {
		return switch (kind) {
			case MOVE -> new MoveToTask(target);
			case MINE -> new MineTask(target);
			case PLACE -> new PlaceTask(target, placeState);
		};
	}

	private static Result reject(String key) {
		return new Result(false, 0, Component.translatable(key));
	}
}
