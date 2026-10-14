package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.registry.ModEntities;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.Vec3;

/** Shared worker spawning for {@code /rdp spawn} and the RTS spawn order. */
public final class WorkerSpawner {
	private WorkerSpawner() {
	}

	/**
	 * Creates a worker with its feet at {@code feet} and adds it to {@code level}.
	 * Does not check for room; callers that need it use {@link #hasRoom}.
	 *
	 * @param commander the player to remember as the worker's commander, or null for none
	 * @return the new worker, or null if it could not be created or added
	 */
	public static @Nullable WorkerEntity spawn(ServerLevel level, Vec3 feet, float yaw, @Nullable UUID commander) {
		WorkerEntity worker = ModEntities.WORKER.create(level, EntitySpawnReason.COMMAND);
		if (worker == null) {
			return null;
		}
		worker.snapTo(feet.x, feet.y, feet.z, yaw, 0.0F);
		if (!level.addFreshEntity(worker)) {
			return null;
		}
		if (commander != null) {
			worker.addCommander(commander);
		}
		return worker;
	}

	/** The feet position of a worker standing in the centre of {@code spot}, on its floor. */
	public static Vec3 feetOn(BlockPos spot) {
		return Vec3.atBottomCenterOf(spot);
	}

	/** Whether a worker's bounding box with its feet at {@code feet} collides with nothing. */
	public static boolean hasRoom(CollisionGetter level, Vec3 feet) {
		return level.noCollision(ModEntities.WORKER.getDimensions().makeBoundingBox(feet));
	}
}
