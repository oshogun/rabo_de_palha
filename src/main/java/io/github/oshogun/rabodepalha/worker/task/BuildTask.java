package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.oshogun.rabodepalha.mixin.StructureTemplateAccessor;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Builds the structure template {@link #blueprint()} (an {@code .nbt} saved by a structure block)
 * with its origin corner at {@link #origin()}, turned by {@link #rotation()}, one {@link PlaceTask}
 * per block from the worker's inventory.
 *
 * <p>Every blueprint state goes through {@link #normalize} first: waterlogged blocks are placed dry,
 * pistons retracted, and a chest half whose partner is not in the blueprint as a single chest. Air,
 * structure voids and second halves of doors and beds are ignored; blocks without an item (piston
 * heads, moving pistons, fire, ...) are counted as unplaceable. Blocks already matching are skipped.
 * The materials for everything left are checked up front: if any are short the job fails with
 * {@link FailureReason#MISSING_ITEM} listing them, before placing anything. Blocks are placed
 * free-standing first, then attached ones, each group bottom-up. Only the parameters are persisted:
 * after a load the blueprint is compared with the world again.
 */
public final class BuildTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "build";
	/** Largest blueprint, in placeable blocks, a single task builds. */
	public static final int MAX_ENTRIES = 4096;
	/** Entries found already correct when popped are skipped back to back up to this many per tick. */
	private static final int MAX_PICKS_PER_TICK = 32;
	private static final int MAX_LISTED = 8;

	/** A normalized blueprint state; {@code dried} if it was waterlogged and is placed dry. */
	record Normalized(BlockState state, boolean dried) {
	}

	private record Entry(BlockPos pos, BlockState state, boolean dried) {
	}

	private final BlockPos origin;
	private final Identifier blueprint;
	private final Rotation rotation;

	private TaskOutcome startFailure;
	private Deque<Entry> queue = new ArrayDeque<>();
	private PlaceTask sub;
	private Entry subEntry;
	/** Entries that could not be reached, were obstructed or could not survive yet; retried once something was placed since. */
	private final List<Entry> deferred = new ArrayList<>();
	/** Why each deferred entry was last deferred, by position. */
	private final Map<BlockPos, FailureReason> deferredReasons = new HashMap<>();
	/** Entries that already failed {@link FailureReason#CANNOT_SURVIVE} once. */
	private final Set<BlockPos> survivalDeferred = new HashSet<>();
	private final Map<BlockPos, String> skipped = new LinkedHashMap<>();
	private List<String> unplaceable = List.of();
	private int placed;
	private int alreadyCorrect;
	private int dried;
	private boolean progressSinceDefer;
	private TaskOutcome.Failed lastApproachFailure;

	public BuildTask(BlockPos origin, Identifier blueprint, Rotation rotation) {
		this.origin = origin.immutable();
		this.blueprint = blueprint;
		this.rotation = rotation;
	}

	public BlockPos origin() {
		return origin;
	}

	public Identifier blueprint() {
		return blueprint;
	}

	public Rotation rotation() {
		return rotation;
	}

	@Override
	public void start(WorkerEntity worker) {
		startFailure = null;
		queue = new ArrayDeque<>();
		sub = null;
		subEntry = null;
		deferred.clear();
		deferredReasons.clear();
		survivalDeferred.clear();
		skipped.clear();
		unplaceable = List.of();
		placed = 0;
		alreadyCorrect = 0;
		dried = 0;
		progressSinceDefer = false;
		lastApproachFailure = null;
		startFailure = plan(worker);
	}

	/** Fills {@link #queue} with what is left to place; returns a failure, or null to start building. */
	private TaskOutcome plan(WorkerEntity worker) {
		if (!(worker.level() instanceof ServerLevel level)) {
			return TaskOutcome.failed(FailureReason.ERROR, "blueprints are only available on the server");
		}
		Optional<StructureTemplate> template = level.getStructureTemplateManager().get(blueprint);
		if (template.isEmpty()) {
			return TaskOutcome.failed(FailureReason.ERROR, "unknown blueprint " + blueprint);
		}
		List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template.get()).rabodepalha$getPalettes();
		Map<BlockPos, BlockState> rotated = new LinkedHashMap<>();
		if (!palettes.isEmpty()) {
			for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
				BlockPos pos = origin.offset(StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO));
				rotated.put(pos, info.state().rotate(rotation));
			}
		}

		List<Entry> toPlace = new ArrayList<>();
		List<String> unplaceableList = new ArrayList<>();
		Map<Item, Integer> needed = new LinkedHashMap<>();
		for (Map.Entry<BlockPos, BlockState> raw : rotated.entrySet()) {
			BlockPos pos = raw.getKey();
			BlockState state = raw.getValue();
			Optional<Normalized> normalized = normalize(state, pos, rotated);
			if (normalized.isEmpty()) {
				if (!isIgnored(state)) {
					unplaceableList.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " @ " + pos.toShortString());
				}
				continue;
			}
			if (isCorrect(level, pos, normalized.get().state())) {
				alreadyCorrect++;
				continue;
			}
			toPlace.add(new Entry(pos, normalized.get().state(), normalized.get().dried()));
			needed.merge(normalized.get().state().getBlock().asItem(), 1, Integer::sum);
		}
		unplaceable = unplaceableList;
		if (toPlace.size() > MAX_ENTRIES) {
			return TaskOutcome.failed(FailureReason.ERROR, "blueprint " + blueprint + " needs " + toPlace.size()
					+ " blocks placed, more than " + MAX_ENTRIES);
		}

		List<String> shortfall = new ArrayList<>();
		needed.forEach((item, count) -> {
			int missing = count - worker.countItem(item);
			if (missing > 0) {
				shortfall.add(missing + " " + BuiltInRegistries.ITEM.getKey(item));
			}
		});
		if (!shortfall.isEmpty()) {
			return TaskOutcome.failed(FailureReason.MISSING_ITEM, "building " + blueprint + " needs "
					+ String.join(", ", shortfall) + " more");
		}

		Map<BlockPos, Entry> byPos = new LinkedHashMap<>();
		List<BuildOrder.Entry> order = new ArrayList<>();
		for (Entry entry : toPlace) {
			byPos.put(entry.pos(), entry);
			order.add(new BuildOrder.Entry(entry.pos(), isAttached(entry.state())));
		}
		for (BuildOrder.Entry entry : BuildOrder.order(order)) {
			queue.add(byPos.get(entry.pos()));
		}
		return null;
	}

	/**
	 * The state to place for blueprint entry {@code state} at {@code pos}, or empty if nothing is placed
	 * there. {@code state} and {@code pos} are already rotated into the world, and {@code blueprint} maps
	 * every rotated entry by its world position.
	 *
	 * <ul>
	 * <li>Empty for air, structure voids, piston heads, moving pistons, second halves of two-part blocks
	 * (placing the first half places both) and states without an item.
	 * <li>Waterlogged states are placed dry, so one item never creates a water source.
	 * <li>Pistons are placed retracted.
	 * <li>A chest half whose partner is not at the matching position in {@code blueprint} (same block,
	 * opposite type, same facing) becomes a single chest.
	 * </ul>
	 */
	static Optional<Normalized> normalize(BlockState state, BlockPos pos, Map<BlockPos, BlockState> blueprint) {
		if (isIgnored(state) || state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)
				|| state.getBlock().asItem() == Items.AIR) {
			return Optional.empty();
		}
		boolean dried = false;
		if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
			state = state.setValue(BlockStateProperties.WATERLOGGED, false);
			dried = true;
		}
		if (state.hasProperty(BlockStateProperties.EXTENDED)) {
			state = state.setValue(BlockStateProperties.EXTENDED, false);
		}
		if (state.getBlock() instanceof ChestBlock && state.hasProperty(BlockStateProperties.CHEST_TYPE)
				&& state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
			BlockState partner = blueprint.get(pos.relative(ChestBlock.getConnectedDirection(state)));
			boolean paired = partner != null && partner.is(state.getBlock())
					&& partner.getValue(BlockStateProperties.CHEST_TYPE) == state.getValue(BlockStateProperties.CHEST_TYPE).getOpposite()
					&& partner.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING);
			if (!paired) {
				state = state.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE);
			}
		}
		return Optional.of(new Normalized(state, dried));
	}

	/** Entries that are left out without being reported: nothing to place, or placed with their other half. */
	private static boolean isIgnored(BlockState state) {
		return state.isAir() || state.is(Blocks.STRUCTURE_VOID)
				|| state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
						&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
				|| state.hasProperty(BlockStateProperties.BED_PART)
						&& state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
	}

	/** Whether every block placing {@code state} at {@code pos} would set is already there. */
	private static boolean isCorrect(Level level, BlockPos pos, BlockState state) {
		for (PlaceTask.Part part : PlaceTask.parts(pos, state)) {
			if (!level.getBlockState(part.pos()).equals(part.state())) {
				return false;
			}
		}
		return true;
	}

	/** Rough guess at blocks that hang on a neighbour, so they go after the blocks they could hang on. */
	private static boolean isAttached(BlockState state) {
		return state.hasProperty(BlockStateProperties.ATTACH_FACE)
				|| state.hasProperty(BlockStateProperties.HANGING)
				|| state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				|| state.getBlock() instanceof LadderBlock
				|| state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		if (startFailure != null) {
			return startFailure;
		}
		for (int i = 0; i < MAX_PICKS_PER_TICK; i++) {
			if (sub == null) {
				TaskOutcome next = startNext(worker);
				if (next != null) {
					return next;
				}
				if (sub == null) {
					continue;
				}
			}
			TaskOutcome outcome = sub.tick(worker);
			if (!outcome.isTerminal()) {
				return TaskOutcome.RUNNING;
			}
			TaskOutcome ended = endSub(worker, outcome);
			return ended != null ? ended : TaskOutcome.RUNNING;
		}
		return TaskOutcome.RUNNING;
	}

	/**
	 * Starts the next placement and returns null; returns null without starting one when the popped
	 * entry was already correct, or returns the outcome when nothing is left.
	 */
	private TaskOutcome startNext(WorkerEntity worker) {
		Entry entry = queue.poll();
		if (entry != null) {
			if (isCorrect(worker.level(), entry.pos(), entry.state())) {
				alreadyCorrect++;
				return null;
			}
			sub = new PlaceTask(entry.pos(), entry.state());
			subEntry = entry;
			sub.start(worker);
			return null;
		}
		if (!deferred.isEmpty() && progressSinceDefer) {
			// Something was placed since these failed, so a way to them (or support for them) may exist now.
			queue.addAll(deferred);
			deferred.clear();
			progressSinceDefer = false;
			return null;
		}
		if (deferred.isEmpty() && skipped.isEmpty()) {
			return TaskOutcome.succeeded("built " + blueprint + ": " + counts());
		}
		List<String> left = new ArrayList<>(skipped.size() + deferred.size());
		skipped.forEach((pos, problem) -> left.add(problem + " @ " + pos.toShortString()));
		deferred.forEach(e -> left.add(BuiltInRegistries.BLOCK.getKey(e.state().getBlock()) + " @ " + e.pos().toShortString()
				+ " (" + deferredReasons.get(e.pos()).name().toLowerCase(Locale.ROOT) + ")"));
		if (placed == 0 && skipped.isEmpty()) {
			return TaskOutcome.failed(FailureReason.UNREACHABLE, "could not reach any block of " + blueprint
					+ lastFailureNote() + "; " + counts());
		}
		return TaskOutcome.failed(FailureReason.STUCK, "built " + blueprint + " partly: " + counts() + "; "
				+ left.size() + " left: " + listed(left) + lastFailureNote());
	}

	/** Stops the ended placement and records its outcome; returns a terminal outcome for the whole build, or null. */
	private TaskOutcome endSub(WorkerEntity worker, TaskOutcome outcome) {
		PlaceTask ended = sub;
		Entry entry = subEntry;
		sub = null;
		subEntry = null;
		ended.stop(worker, false);
		if (outcome instanceof TaskOutcome.Succeeded) {
			placed++;
			if (entry.dried()) {
				dried++;
			}
			progressSinceDefer = true;
			return null;
		}
		if (!(outcome instanceof TaskOutcome.Failed failed)) {
			return null;
		}
		switch (failed.reason()) {
			case MISSING_ITEM -> {
				// The materials were checked up front, so something took them since.
				return TaskOutcome.failed(FailureReason.MISSING_ITEM, "ran out while building " + blueprint + ": "
						+ failed.detail() + "; " + counts());
			}
			case CANNOT_SURVIVE -> {
				if (survivalDeferred.add(entry.pos())) {
					deferred.add(entry);
					deferredReasons.put(entry.pos(), failed.reason());
				} else {
					skipped.put(entry.pos(), failed.detail());
				}
			}
			case UNREACHABLE, STUCK, TIMEOUT, OBSTRUCTED -> {
				// An obstructing mob or player may move away, so retry like an unreachable entry.
				deferred.add(entry);
				deferredReasons.put(entry.pos(), failed.reason());
				lastApproachFailure = failed;
			}
			default -> skipped.put(entry.pos(), failed.detail());
		}
		return null;
	}

	private String counts() {
		StringBuilder note = new StringBuilder("placed " + placed + ", " + alreadyCorrect + " already correct");
		if (dried > 0) {
			note.append(", ").append(dried).append(" placed dry");
		}
		if (!unplaceable.isEmpty()) {
			note.append(", ").append(unplaceable.size()).append(" unplaceable (").append(listed(unplaceable)).append(")");
		}
		return note.toString();
	}

	private String lastFailureNote() {
		return lastApproachFailure == null ? "" : " (" + lastApproachFailure.reason() + ": " + lastApproachFailure.detail() + ")";
	}

	private static String listed(List<String> items) {
		String shown = items.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
		return items.size() > MAX_LISTED ? shown + " and " + (items.size() - MAX_LISTED) + " more" : shown;
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (sub != null) {
			PlaceTask running = sub;
			sub = null;
			subEntry = null;
			running.stop(worker, cancelled);
		}
	}

	@Override
	public String describe() {
		String turned = rotation == Rotation.NONE ? "" : " turned " + rotation.getSerializedName();
		return "build " + blueprint + " at " + origin.toShortString() + turned;
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
