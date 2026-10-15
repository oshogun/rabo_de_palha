package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Tills and plants the field between {@link #from()} and {@link #to()} with {@link #seed()}, one
 * column at a time in {@link RtsField#columnOrder()}. Each column's field block is its surface (see
 * {@link FieldBlocks#surface}). Grass, ferns, snow layers and similar cover are broken first, drops
 * into the inventory; tillable ground is turned into farmland with a hoe from the inventory (1
 * durability per block), then the seed is planted. Nether wart goes straight onto soul sand. Blocks
 * that can't be worked are skipped and listed when the job ends. Running out of seeds or hoes ends
 * the job with the counts so far. Only the corners and the seed are persisted: after a load the field
 * is scanned again.
 */
public final class PlantFieldTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "plant_field";
	private static final int MAX_LISTED = 8;

	private record Skip(String where, String cause) {
	}

	private final BlockPos from;
	private final BlockPos to;
	private final Item seed;
	private final RtsField field;
	private final Set<Long> ignoredColumns;

	private final Deque<BlockPos> queue = new ArrayDeque<>();
	private final List<Skip> skipped = new ArrayList<>();
	private BlockPos current;
	private Approach approach;
	private int total;
	private int planted;
	private int dry;
	private int cleared;

	public PlantFieldTask(BlockPos from, BlockPos to, Item seed) {
		this(from, to, seed, Set.of());
	}

	/**
	 * A field job that leaves out the columns in {@code ignoredColumns}, keyed by {@link RtsIrrigation#key}:
	 * they are neither planted nor listed as skipped. Used for the water holes of an irrigated field.
	 */
	PlantFieldTask(BlockPos from, BlockPos to, Item seed, Set<Long> ignoredColumns) {
		this.from = from.immutable();
		this.to = to.immutable();
		this.seed = seed;
		this.field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		this.ignoredColumns = Set.copyOf(ignoredColumns);
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

	@Override
	public void start(WorkerEntity worker) {
		queue.clear();
		skipped.clear();
		current = null;
		approach = null;
		planted = 0;
		dry = 0;
		cleared = 0;
		Level level = worker.level();
		for (int[] column : field.columnOrder()) {
			if (ignoredColumns.contains(RtsIrrigation.key(column[0], column[1]))) {
				continue;
			}
			BlockPos ground = FieldBlocks.surface(level, field, column[0], column[1]);
			if (ground == null) {
				skipped.add(new Skip(column[0] + ", " + column[1], "no ground"));
				continue;
			}
			FieldBlocks.Plan plan = FieldBlocks.plan(level, ground, seed);
			if (plan.action() != FieldBlocks.Action.SKIP) {
				queue.add(ground);
			} else if (FieldBlocks.cover(level.getBlockState(ground.above())) == FieldBlocks.Cover.BLOCKED) {
				skip(ground, "blocked by " + name(level.getBlockState(ground.above())));
			} else {
				skip(ground, "wrong block " + name(level.getBlockState(ground)));
			}
		}
		total = queue.size();
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		Level level = worker.level();
		if (current == null) {
			current = queue.poll();
			if (current == null) {
				return finish();
			}
			TaskOutcome missing = missing(worker, FieldBlocks.plan(level, current, seed));
			if (missing != null) {
				return missing;
			}
			approach = Approach.toWorkOn(current, Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				skip(current, "unreachable: " + approach.failure().detail());
				next(worker);
				return TaskOutcome.RUNNING;
			case ARRIVED:
				break;
		}
		BlockPos ground = current;
		next(worker);
		FieldBlocks.Plan plan = FieldBlocks.plan(level, ground, seed);
		if (plan.action() == FieldBlocks.Action.SKIP) {
			skip(ground, "changed to " + name(level.getBlockState(ground)));
			return TaskOutcome.RUNNING;
		}
		// Both checks come before clearing, so a job that can't go on leaves the cover alone.
		TaskOutcome missing = missing(worker, plan);
		if (missing != null) {
			return missing;
		}
		ItemStack hoe = plan.action() == FieldBlocks.Action.TILL ? FieldBlocks.findHoe(worker) : ItemStack.EMPTY;

		BlockPos above = ground.above();
		worker.getLookControl().setLookAt(Vec3.atCenterOf(above));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (plan.clearFirst() && level instanceof ServerLevel serverLevel) {
			FieldBlocks.clear(serverLevel, above, worker);
			cleared++;
		}
		if (plan.action() == FieldBlocks.Action.TILL) {
			till(worker, ground, hoe);
		}

		if (!plant(worker, above, seed)) {
			skip(ground, "cannot survive there");
			return TaskOutcome.RUNNING;
		}
		planted++;
		if (level.getBlockState(ground).is(Blocks.FARMLAND) && !FieldBlocks.nearWater(level, ground)) {
			dry++;
		}
		return TaskOutcome.RUNNING;
	}

	/** Turns {@code ground} into farmland with {@code hoe}, which loses 1 durability. */
	static void till(WorkerEntity worker, BlockPos ground, ItemStack hoe) {
		Level level = worker.level();
		BlockState farmland = Blocks.FARMLAND.defaultBlockState();
		level.setBlockAndUpdate(ground, farmland);
		level.playSound(null, ground, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(GameEvent.BLOCK_CHANGE, ground, GameEvent.Context.of(worker, farmland));
		// A broken hoe is gone from the inventory, so the next findHoe returns the next one.
		hoe.hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
		worker.getInventory().setChanged();
	}

	/** Plants one {@code seed} from the inventory at {@code above}; false, with nothing spent, when it can't survive there. */
	static boolean plant(WorkerEntity worker, BlockPos above, Item seed) {
		Level level = worker.level();
		BlockState crop = ((BlockItem) seed).getBlock().defaultBlockState();
		if (!crop.canSurvive(level, above)) {
			return false;
		}
		worker.consumeOne(seed);
		level.setBlockAndUpdate(above, crop);
		level.gameEvent(GameEvent.BLOCK_PLACE, above, GameEvent.Context.of(worker, crop));
		level.playSound(null, above, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		return true;
	}

	/** The outcome that ends the job when the worker lacks what {@code plan} needs, or null. */
	private TaskOutcome missing(WorkerEntity worker, FieldBlocks.Plan plan) {
		if (worker.countItem(seed) == 0) {
			return TaskOutcome.failed(FailureReason.MISSING_ITEM, counts() + ": out of " + seedName() + skippedNote());
		}
		if (plan.action() == FieldBlocks.Action.TILL && FieldBlocks.findHoe(worker).isEmpty()) {
			return planted == 0
					? TaskOutcome.failed(FailureReason.NO_TOOL, "no hoe to till " + area() + skippedNote())
					: TaskOutcome.failed(FailureReason.NO_TOOL, counts() + ": out of hoes" + skippedNote());
		}
		return null;
	}

	private TaskOutcome finish() {
		if (total > 0 && planted == 0) {
			return TaskOutcome.failed(FailureReason.UNREACHABLE, counts() + skippedNote());
		}
		String water = dry > 0 ? "; " + dry + " crops have no water nearby and will grow slowly" : "";
		String clearedNote = cleared > 0 ? "; cleared " + cleared + " plants" : "";
		return TaskOutcome.succeeded(counts() + water + clearedNote + skippedNote());
	}

	private void next(WorkerEntity worker) {
		if (approach != null) {
			approach.stop(worker);
		}
		approach = null;
		current = null;
	}

	private void skip(BlockPos pos, String cause) {
		skipped.add(new Skip(pos.toShortString(), cause));
	}

	private String counts() {
		return "planted " + planted + " of " + total;
	}

	private String seedName() {
		return seed.getDefaultInstance().getHoverName().getString();
	}

	private static String name(BlockState state) {
		return state.getBlock().getName().getString();
	}

	private String skippedNote() {
		if (skipped.isEmpty()) {
			return "";
		}
		return "; skipped " + skipped.size() + ": "
				+ listed(skipped.stream().map(skip -> skip.where() + " (" + skip.cause() + ")").toList());
	}

	private static String listed(List<String> items) {
		String shown = items.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
		return items.size() > MAX_LISTED ? shown + " and " + (items.size() - MAX_LISTED) + " more" : shown;
	}

	private String area() {
		return "(" + field.minX() + ", " + field.minZ() + ") to (" + field.maxX() + ", " + field.maxZ() + ")";
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			Approach running = approach;
			approach = null;
			current = null;
			running.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "plant " + seedName() + " in " + area();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
