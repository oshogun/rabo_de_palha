package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;
import java.util.Optional;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.WorkerScaffold;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds one {@link ScaffoldPlanner.Plan} for an {@link Approach}: walks to its start, then pillars up by
 * jumping and placing a block underfoot, or bridges a gap by placing the next floor block before stepping
 * onto it, so the worker never walks over air.
 *
 * <p>Every placed block is recorded in the worker's {@link WorkerScaffold}, which {@link #claimed claims}
 * the record on the first block; the blocks are cleaned up there once the Approach releases it. Every
 * placement is checked live: never into or over lava, never into an entity, never on another worker's path.
 */
public final class Scaffolding {
	/** Ticks one pillar or bridge step may take. */
	public static final int STEP_TICKS = 40;
	/** Ticks the walk to the plan's start may take. */
	public static final int WALK_TICKS = 400;
	/** How far away another worker's path is checked against a placement. */
	private static final double PATH_CHECK_RADIUS = 16.0;
	/** Horizontal distance from the column center under which a pillar jump starts. */
	private static final double CENTER_TOLERANCE = 0.2;
	private static final double PLACE_EPSILON = 1.0E-3;
	private static final double SPEED = 1.0;

	public enum State { RUNNING, DONE, FAILED }

	private enum Phase { WALK, BUILD, DONE }

	private final ScaffoldPlanner.Plan plan;
	private final List<BlockPos> cells;
	private final Item reserved;

	private Phase phase = Phase.WALK;
	private int index;
	private int stepTicks;
	private boolean walking;
	/** Pillar: jumped for the current cell. Bridge: placed the current cell, now stepping onto it. */
	private boolean acted;
	private boolean claimed;
	private TaskOutcome.Failed failure;

	/** Builds {@code plan}, never spending the last unit of {@code reserved} ({@code Items.AIR} for none). */
	public Scaffolding(ScaffoldPlanner.Plan plan, Item reserved) {
		this.plan = plan;
		this.cells = plan.cells();
		this.reserved = reserved;
	}

	public ScaffoldPlanner.Plan plan() {
		return plan;
	}

	/** Whether this claimed the worker's scaffold record, so the Approach must release it when it stops. */
	public boolean claimed() {
		return claimed;
	}

	/** The failure outcome, or null unless {@link #tick} returned {@link State#FAILED}. */
	public TaskOutcome.Failed failure() {
		return failure;
	}

	public State tick(WorkerEntity worker) {
		if (failure != null) {
			return State.FAILED;
		}
		return switch (phase) {
			case WALK -> walk(worker);
			case BUILD -> plan instanceof ScaffoldPlanner.Pillar ? pillarStep(worker) : bridgeStep(worker);
			case DONE -> State.DONE;
		};
	}

	private State walk(WorkerEntity worker) {
		BlockPos start = plan.start();
		if (worker.blockPosition().equals(start) && worker.onGround()) {
			worker.getNavigation().stop();
			worker.scaffold().claim();
			claimed = true;
			phase = Phase.BUILD;
			stepTicks = 0;
			return State.RUNNING;
		}
		if (++stepTicks > WALK_TICKS) {
			return fail(FailureReason.STUCK, "could not get to the scaffold start at " + start.toShortString());
		}
		if (!walking || worker.getNavigation().isDone()) {
			Path path = worker.getNavigation().createPath(start, 0);
			if (path == null || !path.canReach()) {
				if (!walking) {
					return fail(FailureReason.UNREACHABLE, "no path to " + start.toShortString());
				}
				// Mid-air or briefly off the path: retry on a later tick, bounded by WALK_TICKS.
				return State.RUNNING;
			}
			worker.getNavigation().moveTo(path, SPEED);
			walking = true;
		}
		return State.RUNNING;
	}

	/** Feet on {@code cells[index]}: jump, and place that cell once the feet are above it. */
	private State pillarStep(WorkerEntity worker) {
		if (index >= cells.size()) {
			return finishWhenLanded(worker, cells.get(cells.size() - 1).above());
		}
		if (++stepTicks > STEP_TICKS) {
			return fail(FailureReason.STUCK, "stuck while scaffolding");
		}
		BlockPos cell = cells.get(index);
		Vec3 center = Vec3.atBottomCenterOf(cell);
		worker.getLookControl().setLookAt(Vec3.atCenterOf(cell));
		if (!acted) {
			if (!worker.onGround() || !worker.blockPosition().equals(cell)) {
				return State.RUNNING;
			}
			Vec3 offset = worker.position().subtract(center);
			if (offset.horizontalDistance() > CENTER_TOLERANCE) {
				worker.getMoveControl().setWantedPosition(center.x, center.y, center.z, SPEED);
				return State.RUNNING;
			}
			worker.getJumpControl().jump();
			acted = true;
			return State.RUNNING;
		}
		if (worker.position().y >= cell.getY() + 1.0 - PLACE_EPSILON) {
			return place(worker, cell, Optional.empty()) ? nextStep() : State.FAILED;
		}
		if (worker.onGround() && worker.getDeltaMovement().y <= 0 && stepTicks > 2) {
			// Landed without getting high enough (bumped, or the jump was swallowed): jump again.
			acted = false;
		}
		return State.RUNNING;
	}

	/** Feet on the previous bridge block (or the anchor): place the next one, then step onto it. */
	private State bridgeStep(WorkerEntity worker) {
		if (index >= cells.size()) {
			return finishWhenLanded(worker, cells.get(cells.size() - 1).above());
		}
		if (++stepTicks > STEP_TICKS) {
			return fail(FailureReason.STUCK, "stuck while scaffolding");
		}
		BlockPos cell = cells.get(index);
		BlockPos from = index == 0 ? plan.start() : cells.get(index - 1).above();
		BlockPos to = cell.above();
		if (!acted) {
			if (!worker.onGround() || !worker.blockPosition().equals(from)) {
				return State.RUNNING;
			}
			worker.getNavigation().stop();
			worker.getLookControl().setLookAt(Vec3.atCenterOf(cell));
			if (!place(worker, cell, Optional.of(from))) {
				return State.FAILED;
			}
			acted = true;
		}
		if (worker.blockPosition().equals(to) && worker.onGround()) {
			return nextStep();
		}
		Vec3 target = Vec3.atBottomCenterOf(to);
		worker.getMoveControl().setWantedPosition(target.x, target.y, target.z, SPEED);
		return State.RUNNING;
	}

	private State finishWhenLanded(WorkerEntity worker, BlockPos top) {
		if (worker.onGround() && worker.blockPosition().equals(top)) {
			phase = Phase.DONE;
			return State.DONE;
		}
		if (++stepTicks > STEP_TICKS) {
			return fail(FailureReason.STUCK, "stuck while scaffolding");
		}
		return State.RUNNING;
	}

	private State nextStep() {
		index++;
		stepTicks = 0;
		acted = false;
		return State.RUNNING;
	}

	/** Checks {@code cell}, then places a scaffold block there and records it; false (and failed) if not. */
	private boolean place(WorkerEntity worker, BlockPos cell, Optional<BlockPos> standAt) {
		Level level = worker.level();
		if (!safeToPlace(worker, level, cell)) {
			fail(FailureReason.UNREACHABLE, "scaffold blocked at " + cell.toShortString());
			return false;
		}
		Optional<Item> taken = worker.takeScaffoldBlock(reserved);
		if (taken.isEmpty()) {
			fail(FailureReason.UNREACHABLE, "ran out of scaffold blocks at " + cell.toShortString());
			return false;
		}
		Block block = Block.byItem(taken.get());
		BlockState state = block.defaultBlockState();
		if (!level.setBlock(cell, state, Block.UPDATE_ALL)) {
			worker.insertOrDrop(new ItemStack(taken.get()));
			fail(FailureReason.UNREACHABLE, "scaffold blocked at " + cell.toShortString());
			return false;
		}
		worker.scaffold().record(new WorkerScaffold.ScaffoldBlock(cell, block, standAt));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		SoundType sound = state.getSoundType();
		level.playSound(null, cell, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		return true;
	}

	private static boolean safeToPlace(WorkerEntity worker, Level level, BlockPos cell) {
		ScaffoldPlanner.Cells world = ScaffoldPlanner.Cells.of(level);
		if (level.isOutsideBuildHeight(cell) || !world.placeable(cell) || world.lava(cell)
				|| ScaffoldPlanner.lavaBelow(world, cell)) {
			return false;
		}
		if (!level.getEntities(worker, new AABB(cell), entity -> entity.blocksBuilding && !entity.isSpectator()).isEmpty()) {
			return false;
		}
		return !onAnotherWorkersPath(worker, level, cell);
	}

	/** Whether a nearby worker's remaining path walks through {@code cell} (feet at it or just below it). */
	private static boolean onAnotherWorkersPath(WorkerEntity worker, Level level, BlockPos cell) {
		BlockPos below = cell.below();
		for (WorkerEntity other : level.getEntitiesOfClass(WorkerEntity.class,
				worker.getBoundingBox().inflate(PATH_CHECK_RADIUS), other -> other != worker)) {
			Path path = other.getNavigation().getPath();
			if (path == null || path.isDone()) {
				continue;
			}
			for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
				BlockPos node = path.getNode(i).asBlockPos();
				if (node.equals(cell) || node.equals(below)) {
					return true;
				}
			}
		}
		return false;
	}

	private State fail(FailureReason reason, String detail) {
		failure = TaskOutcome.failed(reason, detail);
		return State.FAILED;
	}
}
