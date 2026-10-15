package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Irrigates and plants the field between {@link #from()} and {@link #to()} with {@link #seed()}. When
 * both corners are the same block it is a plot: the 9×9 square around it (see
 * {@link IrrigationBlocks#plotField}). The worker digs the holes {@link IrrigationBlocks#plan} picks,
 * one at a time from beside them, and pours a water bucket into each right away, so no hole is left
 * open. With no water bucket left it fills its empty ones at the nearest source (see
 * {@link Buckets#findSource}), never at its own holes. Then it plants the field like
 * {@link PlantFieldTask}, leaving the holes out. Missing buckets or water fail before anything is dug;
 * filled holes stay when a later step fails. Only the corners and the seed are persisted: after a load
 * the field is planned again, and the holes already filled count as water that is there.
 */
public final class IrrigateTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "irrigate";
	/** Ticks to wait at an infinite source for it to come back before looking for another. */
	private static final int REFILL_WAIT = 40;
	private static final int MAX_LISTED = 8;

	private enum Phase {
		REFILL, DIG, PLANT
	}

	private final BlockPos from;
	private final BlockPos to;
	private final Item seed;
	private final RtsField field;

	private final Deque<RtsIrrigation.Column> holes = new ArrayDeque<>();
	/** The water this task poured, never used to fill buckets. */
	private final Set<BlockPos> poured = new HashSet<>();
	private final Set<Long> pouredColumns = new LinkedHashSet<>();
	private final List<String> skipped = new ArrayList<>();
	private @Nullable TaskOutcome startFailure;
	private Phase phase = Phase.DIG;
	private RtsIrrigation.@Nullable Column current;
	private @Nullable Approach approach;
	private @Nullable MineTask mine;
	private @Nullable BlockPos source;
	private int sourceWait;
	private @Nullable PlantFieldTask plant;

	public IrrigateTask(BlockPos from, BlockPos to, Item seed) {
		this.from = from.immutable();
		this.to = to.immutable();
		this.seed = seed;
		this.field = isPlot()
				? IrrigationBlocks.plotField(from)
				: RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
	}

	public BlockPos from() {
		return from;
	}

	public BlockPos to() {
		return to;
	}

	public Item seed() {
		return seed;
	}

	/** Whether this is a plot: one clicked block, irrigated with a hole at its centre. */
	public boolean isPlot() {
		return from.equals(to);
	}

	/** The field that is irrigated and planted, a plot's 9×9 square included. */
	public RtsField field() {
		return field;
	}

	@Override
	public void start(WorkerEntity worker) {
		holes.clear();
		poured.clear();
		pouredColumns.clear();
		skipped.clear();
		startFailure = null;
		current = null;
		approach = null;
		mine = null;
		source = null;
		sourceWait = 0;
		plant = null;
		phase = Phase.DIG;
		holes.addAll(IrrigationBlocks.plan(worker.level(), field).holes());
		if (holes.isEmpty()) {
			return;
		}
		int water = Buckets.countWater(worker);
		if (water == 0 && Buckets.countEmpty(worker) == 0) {
			startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, "needs a bucket");
		} else if (water < holes.size() && worker.level() instanceof ServerLevel level
				&& Buckets.findSource(level, centre(), Buckets.WATER_RANGE, poured) == null) {
			startFailure = noWater();
		}
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		if (startFailure != null) {
			return startFailure;
		}
		if (!(worker.level() instanceof ServerLevel level)) {
			return TaskOutcome.RUNNING;
		}
		return switch (phase) {
			case REFILL -> refill(worker, level);
			case DIG -> dig(worker, level);
			case PLANT -> plant(worker);
		};
	}

	/** Fills empty buckets at {@link #source} until there is enough water or no empty bucket left. */
	private TaskOutcome refill(WorkerEntity worker, ServerLevel level) {
		int water = Buckets.countWater(worker);
		if (Buckets.countEmpty(worker) == 0 || water >= remainingHoles()) {
			return leaveSource(worker);
		}
		if (source == null) {
			source = Buckets.findSource(level, worker.blockPosition(), Buckets.WATER_RANGE, poured);
			if (source == null) {
				// start() looked from the centre, so a far corner of a large field still finds that water.
				source = Buckets.findSource(level, centre(), Buckets.WATER_RANGE, poured);
			}
			if (source == null) {
				if (water > 0) {
					return leaveSource(worker);
				}
				stopApproach(worker);
				return TaskOutcome.failed(FailureReason.MISSING_ITEM, noWater().detail() + filledNote());
			}
			sourceWait = 0;
			approach = Approach.toWorkOn(source, Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				TaskOutcome.Failed failure = approach.failure();
				stopApproach(worker);
				return TaskOutcome.failed(failure.reason(), "cannot reach water: " + failure.detail() + filledNote());
			case ARRIVED:
				break;
		}
		if (Buckets.isWaterSource(level, source)) {
			worker.getLookControl().setLookAt(Vec3.atCenterOf(source));
			worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			Buckets.fill(worker, level, source);
			sourceWait = 0;
			return TaskOutcome.RUNNING;
		}
		// Taken: an infinite source comes back on the next fluid tick, any other is gone.
		if (water > 0) {
			return leaveSource(worker);
		}
		if (++sourceWait > REFILL_WAIT) {
			stopApproach(worker);
			source = null;
		}
		return TaskOutcome.RUNNING;
	}

	private TaskOutcome leaveSource(WorkerEntity worker) {
		stopApproach(worker);
		source = null;
		phase = Phase.DIG;
		return TaskOutcome.RUNNING;
	}

	/** Digs out the next hole from beside it and pours water in as soon as it is open. */
	private TaskOutcome dig(WorkerEntity worker, ServerLevel level) {
		if (current == null) {
			current = holes.poll();
			if (current == null) {
				return startPlanting(worker);
			}
			if (Buckets.countWater(worker) == 0) {
				// Back in the queue until the buckets are full again.
				holes.addFirst(current);
				current = null;
				if (Buckets.countEmpty(worker) == 0) {
					return TaskOutcome.failed(FailureReason.MISSING_ITEM, "needs a bucket" + filledNote());
				}
				phase = Phase.REFILL;
				return TaskOutcome.RUNNING;
			}
			BlockPos ground = groundOf(current);
			if (!IrrigationBlocks.holdsWater(level, ground)) {
				skipHole(worker, ground, "would not hold water");
				return TaskOutcome.RUNNING;
			}
			approach = Approach.toWorkOnFromBeside(ground, Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
		}
		BlockPos ground = groundOf(current);
		if (mine == null) {
			switch (approach.tick(worker)) {
				case PATHING:
					return TaskOutcome.RUNNING;
				case FAILED:
					skipHole(worker, ground, "unreachable: " + approach.failure().detail());
					return TaskOutcome.RUNNING;
				case ARRIVED:
					break;
			}
			stopApproach(worker);
			if (!IrrigationBlocks.holdsWater(level, ground)) {
				skipHole(worker, ground, "would not hold water");
				return TaskOutcome.RUNNING;
			}
			// The cover would pop off once its ground is gone; break it first so the drops go to the inventory.
			if (FieldBlocks.cover(level.getBlockState(ground.above())) == FieldBlocks.Cover.CLEARABLE) {
				FieldBlocks.clear(level, ground.above(), worker);
			}
			mine = MineTask.fromBeside(ground);
			mine.start(worker);
		}
		TaskOutcome mined = mine.tick(worker);
		if (mined instanceof TaskOutcome.Running) {
			return TaskOutcome.RUNNING;
		}
		mine.stop(worker, false);
		mine = null;
		if (mined instanceof TaskOutcome.Failed failed) {
			skipHole(worker, ground, failed.detail());
			return TaskOutcome.RUNNING;
		}
		if (!Buckets.pour(worker, level, ground)) {
			current = null;
			return TaskOutcome.failed(FailureReason.OBSTRUCTED,
					"water would not stay at " + ground.toShortString() + filledNote());
		}
		poured.add(ground);
		pouredColumns.add(current.key());
		current = null;
		return TaskOutcome.RUNNING;
	}

	private TaskOutcome startPlanting(WorkerEntity worker) {
		// A plot's corners are its 9×9 square at the clicked height, which gives the same field.
		BlockPos plantFrom = isPlot() ? new BlockPos(field.minX(), from.getY(), field.minZ()) : from;
		BlockPos plantTo = isPlot() ? new BlockPos(field.maxX(), from.getY(), field.maxZ()) : to;
		plant = new PlantFieldTask(plantFrom, plantTo, seed, pouredColumns);
		plant.start(worker);
		phase = Phase.PLANT;
		return plant(worker);
	}

	private TaskOutcome plant(WorkerEntity worker) {
		TaskOutcome outcome = plant.tick(worker);
		if (outcome instanceof TaskOutcome.Succeeded succeeded) {
			return TaskOutcome.succeeded(holesNote() + succeeded.note());
		}
		if (outcome instanceof TaskOutcome.Failed failed) {
			return TaskOutcome.failed(failed.reason(), holesNote() + failed.detail());
		}
		return outcome;
	}

	private void skipHole(WorkerEntity worker, BlockPos ground, String cause) {
		stopApproach(worker);
		skipped.add(ground.toShortString() + " (" + cause + ")");
		current = null;
	}

	private void stopApproach(WorkerEntity worker) {
		if (approach != null) {
			Approach running = approach;
			approach = null;
			running.stop(worker);
		}
	}

	private int remainingHoles() {
		return holes.size() + (current != null ? 1 : 0);
	}

	private BlockPos centre() {
		return new BlockPos((field.minX() + field.maxX()) / 2, from.getY(), (field.minZ() + field.maxZ()) / 2);
	}

	private static BlockPos groundOf(RtsIrrigation.Column column) {
		return new BlockPos(column.x(), column.y(), column.z());
	}

	private static TaskOutcome.Failed noWater() {
		return TaskOutcome.failed(FailureReason.MISSING_ITEM, "no water within " + Buckets.WATER_RANGE + " blocks");
	}

	/** "dug and filled N holes; ", with the holes that were skipped. */
	private String holesNote() {
		String skippedNote = skipped.isEmpty() ? "" : " (skipped " + skipped.size() + ": " + listed(skipped) + ")";
		return "dug and filled " + poured.size() + " holes" + skippedNote + "; ";
	}

	/** The holes filled so far, for a failure before planting. */
	private String filledNote() {
		return poured.isEmpty() ? "" : "; dug and filled " + poured.size() + " holes";
	}

	private static String listed(List<String> items) {
		String shown = items.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
		return items.size() > MAX_LISTED ? shown + " and " + (items.size() - MAX_LISTED) + " more" : shown;
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		stopApproach(worker);
		if (mine != null) {
			MineTask running = mine;
			mine = null;
			running.stop(worker, cancelled);
		}
		if (plant != null) {
			plant.stop(worker, cancelled);
		}
	}

	@Override
	public String describe() {
		return "irrigate and plant " + seed.getDefaultInstance().getHoverName().getString() + " in ("
				+ field.minX() + ", " + field.minZ() + ") to (" + field.maxX() + ", " + field.maxZ() + "), "
				+ field.label();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
