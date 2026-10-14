package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskRunner;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Server-driven worker. Tasks are ticked from {@link #customServerAiStep}, not by goals or the brain;
 * the only goals are floating and idly looking around so they never fight task navigation.
 */
public class WorkerEntity extends PathfinderMob implements InventoryCarrier {
	public static final int INVENTORY_SIZE = 18;
	/** Most commanders remembered per worker; the oldest is forgotten first. */
	public static final int MAX_COMMANDERS = 8;
	private static final String TASKS_TAG = "Tasks";
	private static final String COMMANDERS_TAG = "Commanders";

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private final TaskRunner<WorkerEntity> tasks = new TaskRunner<>();
	/**
	 * Players who gave this worker commands; told about terminal outcomes while online. Saved, capped at
	 * {@link #MAX_COMMANDERS}, oldest first and most recent last.
	 */
	private final Set<UUID> commanders = new LinkedHashSet<>();
	/** Chunk of the held ticket, or null; not saved, the first tick after loading re-acquires it. */
	@Nullable
	private ChunkPos ticketChunk;
	private boolean chunkTicketRefused;
	private int ticksSinceTicketRefresh;

	public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setCanPickUpLoot(false);
		tasks.addListener((task, outcome) -> {
			if (outcome instanceof TaskOutcome.Failed) {
				RaboDePalha.LOGGER.warn("Worker {} task {} -> {}", getStringUUID(), task.describe(), outcome);
			} else if (!notifiesCommanders(outcome)) {
				// The commander cancelled it themselves; routine, so keep it out of the INFO log.
				RaboDePalha.LOGGER.debug("Worker {} task {} -> {}", getStringUUID(), task.describe(), outcome);
			} else {
				RaboDePalha.LOGGER.info("Worker {} task {} -> {}", getStringUUID(), task.describe(), outcome);
			}
		});
		tasks.addListener((task, outcome) -> {
			if (notifiesCommanders(outcome)) {
				notifyCommanders(task.describe() + " -> " + describeOutcome(outcome));
			}
		});
	}

	public static String describeOutcome(TaskOutcome outcome) {
		return OutcomeMessages.describe(outcome);
	}

	/** See {@link OutcomeMessages#notifiesCommanders}. */
	public static boolean notifiesCommanders(TaskOutcome outcome) {
		return OutcomeMessages.notifiesCommanders(outcome);
	}

	/** Remembers {@code player} so it is told about this worker's terminal task outcomes. */
	public void addCommander(UUID player) {
		// Re-adding moves the player to most recent.
		commanders.remove(player);
		commanders.add(player);
		Iterator<UUID> oldest = commanders.iterator();
		while (commanders.size() > MAX_COMMANDERS) {
			oldest.next();
			oldest.remove();
		}
	}

	/** Read-only view of the commanders, oldest first. */
	public Set<UUID> commanders() {
		return Collections.unmodifiableSet(commanders);
	}

	private void notifyCommanders(String message) {
		if (commanders.isEmpty() || !(level() instanceof ServerLevel serverLevel)) {
			return;
		}
		Component text = Component.literal("[" + getName().getString() + " " + getId() + "] " + message);
		for (UUID uuid : commanders) {
			ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(uuid);
			if (player != null) {
				player.sendSystemMessage(text);
			}
		}
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(8, new RandomLookAroundGoal(this));
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		tasks.tick(this);
	}

	@Override
	public void tick() {
		super.tick();
		// Called from tick(), not customServerAiStep, so it also runs for NoAI workers.
		if (level() instanceof ServerLevel serverLevel && !isRemoved()) {
			updateChunkTicket(serverLevel);
		}
	}

	/**
	 * Holds a {@link WorkerChunkTickets} ticket on the worker's current chunk while it has a current or
	 * queued task, moving it when the worker crosses into another chunk and refreshing it before it
	 * times out. Releases it once the worker is idle or dying.
	 */
	private void updateChunkTicket(ServerLevel level) {
		if (tasks.isIdle() || !isAlive()) {
			releaseChunkTicket(level);
			return;
		}
		UUID id = getUUID();
		if (!WorkerChunkTickets.BUDGET.tryAcquire(id)) {
			chunkTicketRefused = true;
			return;
		}
		chunkTicketRefused = false;
		ChunkPos chunk = chunkPosition();
		if (!chunk.equals(ticketChunk)) {
			if (ticketChunk != null) {
				WorkerChunkTickets.release(level, ticketChunk, id);
			}
			WorkerChunkTickets.acquire(level, chunk, id);
			ticketChunk = chunk;
			ticksSinceTicketRefresh = 0;
		} else if (++ticksSinceTicketRefresh >= WorkerChunkTickets.REFRESH_INTERVAL) {
			WorkerChunkTickets.refresh(level, chunk);
			ticksSinceTicketRefresh = 0;
		}
	}

	private void releaseChunkTicket(ServerLevel level) {
		UUID id = getUUID();
		if (ticketChunk != null) {
			WorkerChunkTickets.release(level, ticketChunk, id);
			ticketChunk = null;
		}
		WorkerChunkTickets.BUDGET.release(id);
		chunkTicketRefused = false;
	}

	/** True if this worker currently holds a ticket keeping its chunks loaded. */
	public boolean holdsChunkTicket() {
		return ticketChunk != null;
	}

	/** True if this worker is busy but the server-wide ticket budget is full. */
	public boolean chunkTicketRefused() {
		return chunkTicketRefused;
	}

	/** The chunk this worker's ticket is centred on, or null if it holds none. */
	@Nullable
	public ChunkPos ticketChunk() {
		return ticketChunk;
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!level().isClientSide() && reason.shouldDestroy()) {
			// Lets the current task clean up (e.g. clear the crack overlay).
			tasks.clear(this, TaskOutcome.Cancelled.Cause.REMOVED);
		}
		super.remove(reason);
	}

	/**
	 * Releases the chunk ticket. Done here rather than in {@link #remove} because vanilla unloads
	 * entities and moves them between dimensions with {@code setRemoved}, which skips {@code remove}
	 * but always calls this.
	 */
	@Override
	public void onRemoval(RemovalReason reason) {
		super.onRemoval(reason);
		if (level() instanceof ServerLevel serverLevel) {
			if (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION) {
				releaseChunkTicket(serverLevel);
			} else {
				// Unloaded: keep the persisted ticket so the busy worker comes back after a restart;
				// drop only the in-memory state, which its next first tick re-registers.
				if (ticketChunk != null) {
					WorkerChunkTickets.forget(serverLevel, ticketChunk, getUUID());
					ticketChunk = null;
				}
				WorkerChunkTickets.BUDGET.release(getUUID());
				chunkTicketRefused = false;
			}
		}
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		super.dropCustomDeathLoot(level, source, killedByPlayer);
		inventory.removeAllItems().forEach(stack -> spawnAtLocation(level, stack));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		writeInventoryToTag(output);
		ValueOutput.TypedOutputList<Task<WorkerEntity>> saved = output.list(TASKS_TAG, TaskTypes.CODEC);
		tasks.snapshot().forEach(saved::add);
		ValueOutput.TypedOutputList<UUID> savedCommanders = output.list(COMMANDERS_TAG, UUIDUtil.CODEC);
		commanders.forEach(savedCommanders::add);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		readInventoryFromTag(input);
		// Undecodable entries are skipped and reported by the input's ProblemReporter.
		tasks.restore(input.listOrEmpty(TASKS_TAG, TaskTypes.CODEC).stream().toList());
		// Missing in saves from before commanders were persisted; that loads as no commanders.
		commanders.clear();
		input.listOrEmpty(COMMANDERS_TAG, UUIDUtil.CODEC).forEach(this::addCommander);
	}

	@Override
	public SimpleContainer getInventory() {
		return inventory;
	}

	public TaskRunner<WorkerEntity> tasks() {
		return tasks;
	}

	/**
	 * Adds the stack to the inventory and drops whatever does not fit at the worker's feet.
	 *
	 * @return the part that did not fit (already dropped), or {@link ItemStack#EMPTY}
	 */
	public ItemStack insertOrDrop(ItemStack stack) {
		ItemStack remainder = inventory.addItem(stack);
		if (!remainder.isEmpty() && level() instanceof ServerLevel serverLevel) {
			spawnAtLocation(serverLevel, remainder.copy());
		}
		return remainder;
	}

	public boolean hasItem(Item item) {
		return inventory.hasAnyMatching(stack -> stack.is(item));
	}

	public int countItem(Item item) {
		return inventory.countItem(item);
	}

	/** Removes one of {@code item} from the inventory; returns false if there was none. */
	public boolean consumeOne(Item item) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				stack.shrink(1);
				inventory.setChanged();
				return true;
			}
		}
		return false;
	}

	/**
	 * The inventory stack best suited to break {@code state}: correct-for-drops tools first,
	 * then the highest destroy speed. Returns {@link ItemStack#EMPTY} if nothing beats bare hands.
	 */
	public ItemStack bestToolFor(BlockState state) {
		ItemStack best = ItemStack.EMPTY;
		boolean bestCorrect = false;
		float bestSpeed = 1.0F;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			boolean correct = stack.isCorrectToolForDrops(state);
			float speed = stack.getDestroySpeed(state);
			boolean better = correct != bestCorrect ? correct : speed > bestSpeed;
			if (better && (correct || speed > 1.0F)) {
				best = stack;
				bestCorrect = correct;
				bestSpeed = speed;
			}
		}
		return best;
	}
}
