package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.task.ReportLatch;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Tends the field between {@link #from()} and {@link #to()} until the order is cancelled: a repeating
 * order (see {@link Task#repeating}). Each round scans the field once, then works through what
 * {@link TendPlanner} picks: re-till dirt that dried back from farmland (with a hoe, and only when there
 * is a seed for it), plant empty farmland, harvest ripe plants and replant them (see
 * {@link HarvestAreaTask#harvest}). When the drops no longer fit, the produce goes into the optional
 * {@link #container()}, keeping every tool and one stack of each seed the field plants. With nothing left
 * to do the worker stands still and only counts down {@link #WAIT_TICKS} before the next scan.
 *
 * <p>When ripe plants are left because the produce has nowhere to go, commanders get one notice per
 * distinct problem, not one per round. Only the corners and the container are persisted.
 */
public final class TendFarmTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "tend_farm";
	/** Ticks between two scans of a field with nothing to do. */
	public static final int WAIT_TICKS = 100;
	public static final String NOTICE_PREFIX = "rabodepalha.task.tend_farm.notice.";

	private enum Phase {
		SCAN, WORK, DEPOSIT, WAIT
	}

	private final BlockPos from;
	private final BlockPos to;
	private final Optional<BlockPos> container;
	private final RtsField field;
	private final List<int[]> order;
	// Kept across suspends and rounds so a problem is reported once.
	private final ReportLatch notices = new ReportLatch();

	private final List<TendPlanner.Column> columns = new ArrayList<>();
	private final List<HarvestBlocks.@Nullable Target> ripe = new ArrayList<>();
	private final List<@Nullable BlockPos> grounds = new ArrayList<>();
	/** The seeds of the plants seen in the field, in column order. */
	private final Set<Item> fieldSeeds = new LinkedHashSet<>();

	private Phase phase = Phase.SCAN;
	private TendPlanner.@Nullable Action action;
	private @Nullable Approach approach;
	private int waitTicks;
	/**
	 * A harvest found no room for its drops. Kept across scans until the inventory changes, so a worker with
	 * one empty slot neither walks to the same plant every round nor reports the problem again.
	 */
	private boolean noRoom;
	/** {@link #inventoryStamp} when {@link #noRoom} was set. */
	private int noRoomStamp;
	/** A deposit moved nothing this round; the container counts as full until the next scan. */
	private boolean depositFailed;

	public TendFarmTask(BlockPos from, BlockPos to, Optional<BlockPos> container) {
		this.from = from.immutable();
		this.to = to.immutable();
		this.container = container.map(BlockPos::immutable);
		this.field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		this.order = field.columnOrder();
	}

	public BlockPos from() {
		return from;
	}

	public BlockPos to() {
		return to;
	}

	/** The container produce goes to; empty means the worker keeps it. */
	public Optional<BlockPos> container() {
		return container;
	}

	@Override
	public boolean repeating() {
		return true;
	}

	@Override
	public void start(WorkerEntity worker) {
		phase = Phase.SCAN;
		action = null;
		approach = null;
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		switch (phase) {
			case SCAN -> {
				if (noRoom && inventoryStamp(worker.getInventory()) != noRoomStamp) {
					noRoom = false;
				}
				scan(worker.level());
				phase = Phase.WORK;
				work(worker);
			}
			case WORK -> work(worker);
			case DEPOSIT -> deposit(worker);
			case WAIT -> {
				if (--waitTicks <= 0) {
					phase = Phase.SCAN;
				}
			}
		}
		return TaskOutcome.RUNNING;
	}

	private void scan(Level level) {
		columns.clear();
		ripe.clear();
		grounds.clear();
		depositFailed = false;
		for (int i = 0; i < order.size(); i++) {
			columns.add(TendPlanner.Column.OTHER);
			ripe.add(null);
			grounds.add(null);
			classify(level, i);
		}
	}

	/** Reads column {@code i} from the world into {@link #columns}, {@link #ripe} and {@link #grounds}. */
	private void classify(Level level, int i) {
		int x = order.get(i)[0];
		int z = order.get(i)[1];
		List<HarvestBlocks.Target> found = new ArrayList<>(1);
		int unripe = HarvestBlocks.scan(level, field, x, z, found);
		for (HarvestBlocks.Target target : found) {
			learnSeed(level.getBlockState(target.pos()));
		}
		BlockPos ground = FieldBlocks.surface(level, field, x, z);
		grounds.set(i, ground);
		ripe.set(i, found.isEmpty() ? null : found.get(0));
		if (ground != null) {
			learnSeed(level.getBlockState(ground.above()));
		}
		columns.set(i, column(level, ground, !found.isEmpty(), unripe > 0));
	}

	private static TendPlanner.Column column(Level level, @Nullable BlockPos ground, boolean hasRipe, boolean hasUnripe) {
		if (hasRipe) {
			return TendPlanner.Column.RIPE;
		}
		if (hasUnripe) {
			return TendPlanner.Column.GROWING;
		}
		if (ground == null || !level.getBlockState(ground.above()).isAir()) {
			return TendPlanner.Column.OTHER;
		}
		BlockState state = level.getBlockState(ground);
		if (state.is(Blocks.FARMLAND)) {
			return TendPlanner.Column.EMPTY_FARMLAND;
		}
		// Farmland dries back to plain dirt; grass and other tillable blocks are left alone.
		return state.is(Blocks.DIRT) ? TendPlanner.Column.DRY_DIRT : TendPlanner.Column.OTHER;
	}

	private void learnSeed(BlockState plant) {
		Item seed = HarvestBlocks.replantItem(plant);
		if (seed != null) {
			fieldSeeds.add(seed);
		}
	}

	private void work(WorkerEntity worker) {
		if (approach == null) {
			TendPlanner.Action next = decide(worker);
			BlockPos target = switch (next) {
				case TendPlanner.Harvest harvest -> ripe.get(harvest.index()).pos();
				case TendPlanner.Plant plant -> grounds.get(plant.index());
				case TendPlanner.Till till -> grounds.get(till.index());
				case TendPlanner.Deposit deposit -> container.orElseThrow();
				case TendPlanner.Wait wait -> null;
			};
			if (target == null) {
				startWaiting(worker);
				return;
			}
			action = next;
			approach = Approach.toWorkOn(target, Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
			if (next instanceof TendPlanner.Deposit) {
				phase = Phase.DEPOSIT;
				return;
			}
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return;
			case FAILED:
				// Unreachable this round; the next scan tries it again.
				columns.set(index(action), TendPlanner.Column.OTHER);
				release(worker);
				return;
			case ARRIVED:
				break;
		}
		TendPlanner.Action done = action;
		release(worker);
		int i = index(done);
		Level level = worker.level();
		switch (done) {
			case TendPlanner.Harvest harvest -> {
				switch (HarvestAreaTask.harvest(worker, ripe.get(i))) {
					case NO_ROOM -> {
						noRoom = true;
						noRoomStamp = inventoryStamp(worker.getInventory());
						return;
					}
					// Real progress: a problem that comes back afterwards is reported again.
					case HARVESTED, REPLANTED -> notices.reset();
					case GONE -> {
					}
				}
			}
			case TendPlanner.Plant plant -> {
				Item seed = plantSeed(worker);
				BlockPos ground = grounds.get(i);
				if (seed != null && level.getBlockState(ground).is(Blocks.FARMLAND) && level.getBlockState(ground.above()).isAir()) {
					swingAt(worker, ground.above());
					if (!PlantFieldTask.plant(worker, ground.above(), seed)) {
						// Too dark or similar: left alone until the next scan instead of retried every tick.
						columns.set(i, TendPlanner.Column.OTHER);
						return;
					}
				}
			}
			case TendPlanner.Till till -> {
				ItemStack hoe = FieldBlocks.findHoe(worker);
				BlockPos ground = grounds.get(i);
				if (!hoe.isEmpty() && level.getBlockState(ground).is(Blocks.DIRT) && level.getBlockState(ground.above()).isAir()) {
					swingAt(worker, ground.above());
					PlantFieldTask.till(worker, ground, hoe);
				}
			}
			default -> {
				return;
			}
		}
		classify(level, i);
	}

	/** Picks the next action from the field and the inventory, and reports a problem once. */
	private TendPlanner.Action decide(WorkerEntity worker) {
		boolean hasSeed = plantSeed(worker) != null;
		// Tilling without a seed to plant would only let the farmland dry back and wear the hoe each round.
		boolean hasHoe = hasSeed && !FieldBlocks.findHoe(worker).isEmpty();
		boolean room = !noRoom && hasEmptySlot(worker.getInventory());
		// The container only matters once the inventory is full, so it isn't looked up otherwise.
		TendPlanner.Sink sink = room ? TendPlanner.Sink.OK : sink(worker);
		Optional<String> problem = TendPlanner.problem(columns, room, sink);
		// Cleared only by a harvest or a deposit, not by a round that merely looks fine.
		if (problem.isPresent() && notices.shouldReport(problem.get())) {
			worker.reportToCommanders(Component.translatable(NOTICE_PREFIX + problem.get()));
		}
		return TendPlanner.next(columns, hasSeed, hasHoe, room, sink);
	}

	private TendPlanner.Sink sink(WorkerEntity worker) {
		if (container.isEmpty()) {
			return TendPlanner.Sink.NONE;
		}
		if (depositFailed) {
			return TendPlanner.Sink.FULL;
		}
		BlockPos pos = container.get();
		Level level = worker.level();
		// Never load a chunk just to look; the deposit checks again on arrival.
		if (!level.isLoaded(pos)) {
			return TendPlanner.Sink.OK;
		}
		Container target = Containers.find(level, pos);
		if (target == null) {
			return TendPlanner.Sink.NOT_A_CONTAINER;
		}
		return hasRoomFor(target, worker.getInventory(), keep(worker.getInventory()))
				? TendPlanner.Sink.OK
				: TendPlanner.Sink.FULL;
	}

	private void deposit(WorkerEntity worker) {
		switch (approach.tick(worker)) {
			case PATHING:
				return;
			case FAILED:
				depositFailed = true;
				release(worker);
				phase = Phase.WORK;
				return;
			case ARRIVED:
				break;
		}
		release(worker);
		phase = Phase.WORK;
		BlockPos pos = container.orElseThrow();
		// Looked up on arrival: the block may have changed while walking. A missing one shows up in the next decide.
		Container target = Containers.find(worker.level(), pos);
		if (target == null) {
			return;
		}
		SimpleContainer inventory = worker.getInventory();
		Set<Integer> keep = keep(inventory);
		DepositTask.Moved moved = DepositTask.insertSlots(target, inventory, i -> !keep.contains(i));
		if (moved.deposited() == 0) {
			depositFailed = true;
			return;
		}
		inventory.setChanged();
		noRoom = false;
		notices.reset();
		swingAt(worker, pos);
	}

	/** The inventory slots a deposit leaves alone: every tool and one stack of each seed the field plants. */
	private Set<Integer> keep(SimpleContainer inventory) {
		List<TendPlanner.Slot> slots = new ArrayList<>(inventory.getContainerSize());
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				slots.add(TendPlanner.Slot.EMPTY);
			} else {
				String seedId = FieldBlocks.isSeed(stack.getItem()) || fieldSeeds.contains(stack.getItem())
						? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
						: null;
				slots.add(new TendPlanner.Slot(stack.isDamageableItem(), seedId));
			}
		}
		Set<String> planted = new LinkedHashSet<>();
		for (Item seed : fieldSeeds) {
			planted.add(BuiltInRegistries.ITEM.getKey(seed).toString());
		}
		return TendPlanner.keepSlots(slots, planted);
	}

	/**
	 * Whether {@code target} has room for at least part of one depositable stack: an empty slot, or a
	 * matching stack below its limit. A rough check that keeps the worker from walking to a full chest.
	 */
	private static boolean hasRoomFor(Container target, SimpleContainer inventory, Set<Integer> keep) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || keep.contains(i)) {
				continue;
			}
			for (int s = 0; s < target.getContainerSize(); s++) {
				ItemStack slot = target.getItem(s);
				if (!target.canPlaceItem(s, stack)) {
					continue;
				}
				if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, stack)
						&& slot.getCount() < Math.min(target.getMaxStackSize(slot), slot.getMaxStackSize())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The seed to plant empty farmland with: the first seed the field grows that the worker carries, or any
	 * farmland seed it carries when the field grows none yet. Null when it has none.
	 */
	private @Nullable Item plantSeed(WorkerEntity worker) {
		boolean fieldHasFarmlandSeed = false;
		for (Item seed : fieldSeeds) {
			if (FieldBlocks.soilOf(seed) == FieldBlocks.Soil.FARMLAND) {
				fieldHasFarmlandSeed = true;
				if (worker.countItem(seed) > 0) {
					return seed;
				}
			}
		}
		if (fieldHasFarmlandSeed) {
			return null;
		}
		SimpleContainer inventory = worker.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			Item item = inventory.getItem(i).getItem();
			if (FieldBlocks.soilOf(item) == FieldBlocks.Soil.FARMLAND) {
				return item;
			}
		}
		return null;
	}

	private static boolean hasEmptySlot(SimpleContainer inventory) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** A cheap fingerprint of the items and counts in {@code inventory}, to notice that it changed. */
	private static int inventoryStamp(SimpleContainer inventory) {
		int stamp = 1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			stamp = 31 * stamp + (stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem()) * 67 + stack.getCount());
		}
		return stamp;
	}

	private static int index(TendPlanner.Action action) {
		return switch (action) {
			case TendPlanner.Harvest harvest -> harvest.index();
			case TendPlanner.Plant plant -> plant.index();
			case TendPlanner.Till till -> till.index();
			case TendPlanner.Deposit deposit -> -1;
			case TendPlanner.Wait wait -> -1;
		};
	}

	private static void swingAt(WorkerEntity worker, BlockPos pos) {
		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
	}

	/** Stops navigation once and counts down to the next scan; nothing else runs while waiting. */
	private void startWaiting(WorkerEntity worker) {
		worker.getNavigation().stop();
		waitTicks = WAIT_TICKS;
		phase = Phase.WAIT;
	}

	private void release(WorkerEntity worker) {
		if (approach != null) {
			Approach running = approach;
			approach = null;
			running.stop(worker);
		}
		action = null;
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		release(worker);
		phase = Phase.SCAN;
	}

	private String area() {
		return "(" + field.minX() + ", " + field.minZ() + ") to (" + field.maxX() + ", " + field.maxZ() + ")";
	}

	@Override
	public String describe() {
		return "tend " + area() + container.map(pos -> " into " + pos.toShortString()).orElse("");
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
