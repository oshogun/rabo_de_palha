package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;

/**
 * Harvests the ripe plants in the field between {@link #from()} and {@link #to()}, one column at a time
 * in {@link RtsField#columnOrder()}, using the rules in {@link HarvestBlocks}. Drops go straight into the
 * worker's inventory; nothing is dropped in the world. Crops, nether wart and cocoa are replanted from
 * the drops or the inventory; stalk bases and gourd stems are kept; sweet berry bushes are picked and
 * reset. Unripe plants are left alone and counted. When the drops of the next plant don't fit, the job
 * ends with the counts so far and the plant is left untouched. Only the corners are persisted: after a
 * load the field is scanned again.
 */
public final class HarvestAreaTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "harvest_area";
	private static final int MAX_LISTED = 8;

	private record Skip(String where, String cause) {
	}

	private final BlockPos from;
	private final BlockPos to;
	private final RtsField field;

	private final Deque<HarvestBlocks.Target> queue = new ArrayDeque<>();
	private final List<Skip> skipped = new ArrayList<>();
	private HarvestBlocks.Target current;
	private Approach approach;
	private int harvested;
	private int replanted;
	private int unripe;

	public HarvestAreaTask(BlockPos from, BlockPos to) {
		this.from = from.immutable();
		this.to = to.immutable();
		this.field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
	}

	public BlockPos from() {
		return from;
	}

	public BlockPos to() {
		return to;
	}

	@Override
	public void start(WorkerEntity worker) {
		queue.clear();
		skipped.clear();
		current = null;
		approach = null;
		harvested = 0;
		replanted = 0;
		unripe = 0;
		Level level = worker.level();
		List<HarvestBlocks.Target> ripe = new ArrayList<>();
		for (int[] column : field.columnOrder()) {
			unripe += HarvestBlocks.scan(level, field, column[0], column[1], ripe);
		}
		queue.addAll(ripe);
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		Level level = worker.level();
		if (current == null) {
			current = queue.poll();
			if (current == null) {
				return finish();
			}
			approach = Approach.toWorkOn(current.pos(), Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				skip(current.pos(), "unreachable: " + approach.failure().detail());
				next(worker);
				return TaskOutcome.RUNNING;
			case ARRIVED:
				break;
		}
		HarvestBlocks.Target target = current;
		next(worker);
		BlockPos pos = target.pos();
		BlockState state = level.getBlockState(pos);
		// Picked, broken or trampled since the scan: nothing to do.
		if (HarvestBlocks.ruleOf(state) != target.rule() || !HarvestBlocks.ripeAt(level, pos, state)
				|| !(level instanceof ServerLevel serverLevel)) {
			return TaskOutcome.RUNNING;
		}

		List<BlockPos> breaks = breakPositions(level, target, state);
		List<ItemStack> drops = new ArrayList<>();
		if (target.rule() == HarvestBlocks.Rule.BERRY) {
			// The bush's own use needs a player; the loot helper takes any entity.
			Block.dropFromBlockInteractLootTable(serverLevel, BuiltInLootTables.HARVEST_SWEET_BERRY_BUSH, pos, state,
					level.getBlockEntity(pos), null, worker, (ignored, stack) -> drops.add(stack));
		} else {
			for (BlockPos p : breaks) {
				drops.addAll(Block.getDrops(level.getBlockState(p), serverLevel, p, level.getBlockEntity(p), worker, ItemStack.EMPTY));
			}
		}

		BlockState replant = HarvestBlocks.replantState(state);
		Item seed = HarvestBlocks.replantItem(state);
		boolean seedFromDrops = false;
		boolean replanting = false;
		if (replant != null && seed != null && replant.canSurvive(level, pos)) {
			seedFromDrops = takeOne(drops, seed);
			replanting = seedFromDrops || worker.countItem(seed) > 0;
		}
		// Checked before anything changes, so a job that can't go on leaves the plant alone.
		if (!worker.fitsAll(drops)) {
			return TaskOutcome.failed(FailureReason.INVENTORY_FULL,
					"harvested " + harvested + ", inventory full; replanted " + replanted + ", unripe " + unripe + skippedNote());
		}

		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (target.rule() == HarvestBlocks.Rule.BERRY) {
			BlockState picked = HarvestBlocks.pickedState(state);
			level.setBlockAndUpdate(pos, picked);
			level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F,
					0.8F + level.getRandom().nextFloat() * 0.4F);
			level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(worker, picked));
		} else {
			// No world drops; destroyBlock still plays the break particles and sound. A gourd's stem
			// reverts to an unattached stem by itself.
			for (BlockPos p : breaks) {
				level.destroyBlock(p, false, worker);
			}
		}
		for (ItemStack drop : drops) {
			worker.getInventory().addItem(drop);
		}
		harvested++;

		if (replanting) {
			if (!seedFromDrops) {
				worker.consumeOne(seed);
			}
			level.setBlockAndUpdate(pos, replant);
			level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(worker, replant));
			level.playSound(null, pos, plantSound(target.rule()), SoundSource.BLOCKS, 1.0F, 1.0F);
			replanted++;
		}
		return TaskOutcome.RUNNING;
	}

	/**
	 * The blocks to break for {@code target}: every block of a stalk above its base, top down so no block
	 * loses its support and pops as a world drop; nothing for a berry bush, which is picked; otherwise
	 * the plant itself.
	 */
	private static List<BlockPos> breakPositions(Level level, HarvestBlocks.Target target, BlockState state) {
		return switch (target.rule()) {
			case STALK -> {
				List<BlockPos> above = new ArrayList<>();
				BlockPos p = target.pos().above();
				while (level.getBlockState(p).is(state.getBlock())) {
					above.add(0, p);
					p = p.above();
				}
				yield above;
			}
			case BERRY -> List.of();
			default -> List.of(target.pos());
		};
	}

	/** Takes one {@code item} out of {@code drops}; false when there is none. */
	private static boolean takeOne(List<ItemStack> drops, Item item) {
		for (Iterator<ItemStack> it = drops.iterator(); it.hasNext();) {
			ItemStack stack = it.next();
			if (stack.is(item)) {
				stack.shrink(1);
				if (stack.isEmpty()) {
					it.remove();
				}
				return true;
			}
		}
		return false;
	}

	private static SoundEvent plantSound(HarvestBlocks.Rule rule) {
		return switch (rule) {
			case NETHER_WART -> SoundEvents.NETHER_WART_PLANTED;
			case COCOA -> SoundEvents.WOOD_PLACE;
			default -> SoundEvents.CROP_PLANTED;
		};
	}

	private TaskOutcome finish() {
		return TaskOutcome.succeeded("harvested " + harvested + ", replanted " + replanted + ", unripe " + unripe + skippedNote());
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
		return "harvest " + area();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
