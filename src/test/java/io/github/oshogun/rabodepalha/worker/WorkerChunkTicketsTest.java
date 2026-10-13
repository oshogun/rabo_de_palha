package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkerChunkTicketsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private static final UUID C = new UUID(0, 3);

	@Test
	void constantsAreConsistent() {
		assertEquals(49, WorkerChunkTickets.CHUNKS_PER_WORKER);
		assertTrue(WorkerChunkTickets.CHUNKS_PER_WORKER <= WorkerChunkTickets.MAX_CHUNKS_PER_WORKER);
		assertEquals(32, WorkerChunkTickets.MAX_TICKETED_WORKERS);
		assertTrue(WorkerChunkTickets.MAX_TICKETED_WORKERS * WorkerChunkTickets.CHUNKS_PER_WORKER
				<= WorkerChunkTickets.MAX_TOTAL_CHUNKS);
		// The ticket times out after 100 ticks; refreshes must come well before that.
		assertTrue(WorkerChunkTickets.REFRESH_INTERVAL < 100);
		assertEquals(WorkerChunkTickets.MAX_TICKETED_WORKERS, WorkerChunkTickets.BUDGET.cap());
	}

	@Test
	void budgetRefusesPastCap() {
		WorkerChunkTickets.Budget budget = new WorkerChunkTickets.Budget(2);

		assertTrue(budget.tryAcquire(A));
		assertTrue(budget.tryAcquire(B));
		assertFalse(budget.tryAcquire(C));
		assertFalse(budget.holds(C));
		assertEquals(2, budget.size());
	}

	@Test
	void budgetAcquireIsIdempotent() {
		WorkerChunkTickets.Budget budget = new WorkerChunkTickets.Budget(1);

		assertTrue(budget.tryAcquire(A));
		assertTrue(budget.tryAcquire(A));
		assertEquals(1, budget.size());
	}

	@Test
	void budgetReleaseFreesSlot() {
		WorkerChunkTickets.Budget budget = new WorkerChunkTickets.Budget(1);
		budget.tryAcquire(A);

		budget.release(A);
		budget.release(A);

		assertFalse(budget.holds(A));
		assertEquals(0, budget.size());
		assertTrue(budget.tryAcquire(B));
	}

	@Test
	void budgetClearEmptiesIt() {
		WorkerChunkTickets.Budget budget = new WorkerChunkTickets.Budget(2);
		budget.tryAcquire(A);
		budget.tryAcquire(B);

		budget.clear();

		assertEquals(0, budget.size());
		assertFalse(budget.holds(A));
	}

	@Test
	void firstHolderAddReturnsTrue() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();

		assertTrue(holders.add(OVERWORLD, 5L, A));
		assertFalse(holders.add(OVERWORLD, 5L, B));
		assertEquals(Set.of(A, B), holders.holders(OVERWORLD, 5L));
	}

	@Test
	void onlyLastHolderRemoveReturnsTrue() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();
		holders.add(OVERWORLD, 5L, A);
		holders.add(OVERWORLD, 5L, B);

		assertFalse(holders.remove(OVERWORLD, 5L, A));
		assertEquals(Set.of(B), holders.holders(OVERWORLD, 5L));
		assertTrue(holders.remove(OVERWORLD, 5L, B));
		assertEquals(Set.of(), holders.holders(OVERWORLD, 5L));
	}

	@Test
	void removingAbsentHolderReturnsFalse() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();

		assertFalse(holders.remove(OVERWORLD, 5L, A));
		holders.add(OVERWORLD, 5L, A);
		assertFalse(holders.remove(OVERWORLD, 5L, B));
		assertTrue(holders.remove(OVERWORLD, 5L, A));
		assertFalse(holders.remove(OVERWORLD, 5L, A));
	}

	@Test
	void addIsIdempotentPerWorker() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();

		assertTrue(holders.add(OVERWORLD, 5L, A));
		assertFalse(holders.add(OVERWORLD, 5L, A));
		assertEquals(Set.of(A), holders.holders(OVERWORLD, 5L));
		assertTrue(holders.remove(OVERWORLD, 5L, A));
	}

	@Test
	void levelsAndChunksAreIndependent() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();

		assertTrue(holders.add(OVERWORLD, 5L, A));
		assertTrue(holders.add(NETHER, 5L, A));
		assertTrue(holders.add(OVERWORLD, 6L, A));

		assertTrue(holders.remove(OVERWORLD, 5L, A));
		assertEquals(Set.of(A), holders.holders(NETHER, 5L));
		assertEquals(Set.of(A), holders.holders(OVERWORLD, 6L));
	}

	@Test
	void holdersReturnsUnmodifiableCopy() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();
		holders.add(OVERWORLD, 5L, A);
		Set<UUID> copy = holders.holders(OVERWORLD, 5L);

		holders.add(OVERWORLD, 5L, B);

		assertEquals(Set.of(A), copy);
		assertThrows(UnsupportedOperationException.class, () -> copy.add(C));
	}

	@Test
	void clearResetsHolders() {
		WorkerChunkTickets.Holders holders = new WorkerChunkTickets.Holders();
		holders.add(OVERWORLD, 5L, A);
		holders.add(NETHER, 6L, B);

		holders.clear();

		assertEquals(Set.of(), holders.holders(OVERWORLD, 5L));
		assertEquals(Set.of(), holders.holders(NETHER, 6L));
		assertTrue(holders.add(OVERWORLD, 5L, C));
	}
}
