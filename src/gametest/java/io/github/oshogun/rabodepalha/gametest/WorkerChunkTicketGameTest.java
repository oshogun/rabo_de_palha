package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.mixin.ServerChunkCacheAccessor;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.registry.ModTicketTypes;
import io.github.oshogun.rabodepalha.worker.WorkerChunkTickets;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Checks the {@link WorkerChunkTickets} ticket a busy worker holds. The budget is server-wide, so
 * these tests run in their own test environments (one batch each, see
 * {@code data/rabodepalha-test/test_environment}), assert only on their own workers, and discard
 * them before succeeding so they leave no budget slots behind.
 *
 * <p>Busy workers that must stay put are NoAI with a queued task: the task never ticks, but
 * {@link WorkerEntity#tick()} still manages the ticket.
 */
public class WorkerChunkTicketGameTest {
	private static final String TICKETS = "rabodepalha-test:worker_tickets";
	private static final String TICKET_CAP = "rabodepalha-test:worker_ticket_cap";
	/** Busy workers the {@link #TICKETS} batch spawns at once, one per test. */
	private static final int TICKETS_BATCH_WORKERS = 8;
	/** Ticks a {@link ModTicketTypes#WORKER} ticket lives without a refresh. */
	private static final int TICKET_TIMEOUT = (int) ModTicketTypes.WORKER.timeout();
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);
	private static final BlockPos TARGET = new BlockPos(5, 1, 5);
	/** Level of a ticket at {@link WorkerChunkTickets#TICKET_RADIUS}. */
	private static final int TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL) - WorkerChunkTickets.TICKET_RADIUS;

	/** Fails if workers from earlier batches still hold so many slots that this batch could be refused. */
	private static void assertBudgetRoom(GameTestHelper helper) {
		int size = WorkerChunkTickets.BUDGET.size();
		helper.assertTrue(size + TICKETS_BATCH_WORKERS <= WorkerChunkTickets.MAX_TICKETED_WORKERS,
				"budget holds " + size + " stale slots");
	}

	/** A NoAI worker with a queued task, so it stays busy where it was spawned. */
	private static WorkerEntity spawnBusyWorker(GameTestHelper helper, BlockPos rel) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, rel);
		worker.setNoAi(true);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(TARGET)));
		return worker;
	}

	private static boolean storageHasTicket(ServerLevel level, ChunkPos chunk) {
		return ((ServerChunkCacheAccessor) level.getChunkSource()).rabodepalha$getTicketStorage()
				.getTickets(chunk.pack()).stream()
				.anyMatch(t -> t.getType() == ModTicketTypes.WORKER && t.getTicketLevel() == TICKET_LEVEL);
	}

	/** A WORKER ticket exists on {@code chunk} if and only if some worker holds it. */
	private static void assertInvariant(GameTestHelper helper, ChunkPos chunk) {
		ServerLevel level = helper.getLevel();
		boolean held = !WorkerChunkTickets.holdersAt(level, chunk).isEmpty();
		helper.assertTrue(held == storageHasTicket(level, chunk),
				"chunk " + chunk + " has holders " + WorkerChunkTickets.holdersAt(level, chunk)
						+ " but storage ticket " + storageHasTicket(level, chunk));
	}

	private static void assertHolds(GameTestHelper helper, WorkerEntity worker) {
		helper.assertTrue(worker.holdsChunkTicket(), "worker holds no ticket");
		ChunkPos chunk = worker.ticketChunk();
		helper.assertTrue(chunk.equals(worker.chunkPosition()), "ticket on " + chunk + ", worker in " + worker.chunkPosition());
		helper.assertTrue(WorkerChunkTickets.holdersAt(helper.getLevel(), chunk).contains(worker.getUUID()),
				"worker missing from the holders of " + chunk);
		helper.assertTrue(storageHasTicket(helper.getLevel(), chunk), "no WORKER ticket on " + chunk);
	}

	private static void assertReleased(GameTestHelper helper, WorkerEntity worker, ChunkPos chunk) {
		helper.assertFalse(worker.holdsChunkTicket(), "worker still holds a ticket");
		helper.assertFalse(WorkerChunkTickets.holdersAt(helper.getLevel(), chunk).contains(worker.getUUID()),
				"worker still among the holders of " + chunk);
		helper.assertFalse(WorkerChunkTickets.BUDGET.holds(worker.getUUID()), "worker still holds a budget slot");
		assertInvariant(helper, chunk);
	}

	@GameTest(environment = TICKETS, maxTicks = 100)
	public void busyWorkerHoldsTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					assertInvariant(helper, worker.ticketChunk());
				})
				.thenExecute(worker::discard)
				.thenSucceed();
	}

	/** Outlives the ticket's own timeout, so only the worker's refreshes can keep the ticket alive. */
	@GameTest(environment = TICKETS, maxTicks = 300)
	public void busyWorkerKeepsTicketPastTimeout(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);

		helper.startSequence()
				.thenWaitUntil(() -> assertHolds(helper, worker))
				.thenIdle(TICKET_TIMEOUT + 50)
				.thenExecute(() -> {
					assertHolds(helper, worker);
					assertInvariant(helper, worker.ticketChunk());
				})
				.thenExecute(worker::discard)
				.thenSucceed();
	}

	@GameTest(environment = TICKETS, maxTicks = 400)
	public void idleWorkerReleasesTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(TARGET)));
		ChunkPos[] chunk = new ChunkPos[1];

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					chunk[0] = worker.ticketChunk();
				})
				.thenWaitUntil(() -> {
					helper.assertTrue(worker.tasks().isIdle(), "worker not idle yet");
					helper.assertFalse(worker.holdsChunkTicket(), "worker still holds a ticket");
				})
				.thenExecute(() -> assertReleased(helper, worker, chunk[0]))
				.thenExecute(worker::discard)
				.thenSucceed();
	}

	@GameTest(environment = TICKETS, maxTicks = 100)
	public void discardedWorkerReleasesTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);
		ChunkPos[] chunk = new ChunkPos[1];

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					chunk[0] = worker.ticketChunk();
				})
				.thenExecute(() -> {
					worker.discard();
					assertReleased(helper, worker, chunk[0]);
				})
				.thenSucceed();
	}

	@GameTest(environment = TICKETS, maxTicks = 100)
	public void killedWorkerReleasesTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);
		ChunkPos[] chunk = new ChunkPos[1];

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					chunk[0] = worker.ticketChunk();
				})
				.thenExecute(() -> worker.kill(helper.getLevel()))
				.thenWaitUntil(() -> helper.assertFalse(worker.holdsChunkTicket(), "dead worker still holds a ticket"))
				.thenExecute(() -> assertReleased(helper, worker, chunk[0]))
				.thenExecute(worker::discard)
				.thenSucceed();
	}

	/** Changing dimension goes through {@code setRemoved}, not {@code remove}, so the ticket must still go. */
	@GameTest(environment = TICKETS, maxTicks = 100)
	public void dimensionChangeReleasesTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);
		ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
		ChunkPos[] chunk = new ChunkPos[1];

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					chunk[0] = worker.ticketChunk();
				})
				.thenExecute(() -> {
					helper.assertTrue(nether != null, "no nether level");
					Entity moved = worker.teleport(new TeleportTransition(nether, worker.position(), Vec3.ZERO, 0, 0,
							TeleportTransition.DO_NOTHING));
					helper.assertTrue(worker.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION,
							"worker removed with " + worker.getRemovalReason());
					assertReleased(helper, worker, chunk[0]);
					if (moved != null) {
						moved.discard();
					}
				})
				.thenSucceed();
	}

	/**
	 * Unloading goes through {@code setRemoved}, not {@code remove}. It keeps the persisted ticket so
	 * the worker comes back after a restart, but drops the worker from the holders and the budget.
	 */
	@GameTest(environment = TICKETS, maxTicks = 100)
	public void unloadedWorkerForgetsTicket(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);
		ChunkPos[] chunk = new ChunkPos[1];

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertHolds(helper, worker);
					chunk[0] = worker.ticketChunk();
				})
				.thenExecute(() -> {
					worker.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
					helper.assertFalse(worker.holdsChunkTicket(), "unloaded worker still holds a ticket");
					helper.assertFalse(WorkerChunkTickets.holdersAt(helper.getLevel(), chunk[0]).contains(worker.getUUID()),
							"unloaded worker still among the holders of " + chunk[0]);
					helper.assertFalse(WorkerChunkTickets.BUDGET.holds(worker.getUUID()), "unloaded worker still holds a budget slot");
					helper.assertTrue(storageHasTicket(helper.getLevel(), chunk[0]), "persisted ticket on " + chunk[0] + " is gone");
					// Drop the orphaned ticket now rather than at its timeout, so it cannot break another test's invariant.
					if (WorkerChunkTickets.holdersAt(helper.getLevel(), chunk[0]).isEmpty()) {
						helper.getLevel().getChunkSource().removeTicketWithRadius(ModTicketTypes.WORKER, chunk[0],
								WorkerChunkTickets.TICKET_RADIUS);
					}
				})
				.thenSucceed();
	}

	@GameTest(environment = TICKETS, maxTicks = 100)
	public void listShowsBusyWorkerHoldingChunks(GameTestHelper helper) {
		assertBudgetRoom(helper);
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = spawnBusyWorker(helper, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.holdsChunkTicket(), "worker holds no ticket yet"))
				.thenExecute(() -> {
					WorkerCommandGameTest.run(helper, WorkerCommandGameTest.sourceAt(helper, rec, worker), "rdp list");
					// NoAI: the task never starts, so it stays queued behind no current task.
					WorkerCommandGameTest.assertFeedback(helper, rec, " " + worker.getId() + " @ "
							+ WorkerCommandGameTest.coords(worker.blockPosition()) + " - idle, 1 queued, chunks loaded");
				})
				.thenExecute(worker::discard)
				.thenSucceed();
	}

	/**
	 * Spawns one busy worker more than the budget allows. Stale slots from earlier batches can only
	 * add refusals, so at least one of this test's workers is always refused.
	 */
	@GameTest(environment = TICKET_CAP, maxTicks = 100)
	public void chunkCapRespected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		ServerLevel level = helper.getLevel();
		List<WorkerEntity> workers = new ArrayList<>();
		for (int i = 0; i <= WorkerChunkTickets.MAX_TICKETED_WORKERS; i++) {
			// Spread out on the 8x8 floor so they do not cram into one block.
			workers.add(spawnBusyWorker(helper, new BlockPos(i % 8, 1, i / 8)));
		}
		Map<WorkerEntity, ChunkPos> held = new HashMap<>();

		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertTrue(WorkerChunkTickets.BUDGET.size() <= WorkerChunkTickets.MAX_TICKETED_WORKERS,
							"budget holds " + WorkerChunkTickets.BUDGET.size() + " slots");
					int refused = 0;
					for (WorkerEntity worker : workers) {
						if (worker.chunkTicketRefused()) {
							refused++;
							helper.assertFalse(worker.holdsChunkTicket(), "refused worker holds a ticket");
							helper.assertFalse(WorkerChunkTickets.holdersAt(level, worker.chunkPosition()).contains(worker.getUUID()),
									"refused worker among the holders of " + worker.chunkPosition());
						} else {
							assertHolds(helper, worker);
							held.put(worker, worker.ticketChunk());
						}
					}
					helper.assertTrue(refused >= 1, "no worker was refused");
					for (ChunkPos chunk : held.values()) {
						assertInvariant(helper, chunk);
					}
				})
				.thenExecute(() -> {
					workers.forEach(WorkerEntity::discard);
					for (WorkerEntity worker : workers) {
						ChunkPos chunk = held.getOrDefault(worker, worker.chunkPosition());
						assertReleased(helper, worker, chunk);
					}
				})
				.thenSucceed();
	}
}
