package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

class DigOrderTest {
	private static final BlockPos MIN = new BlockPos(0, 0, 0);
	private static final BlockPos MAX = new BlockPos(2, 2, 2);
	private static final BlockPos NOWHERE = new BlockPos(100, 100, 100);

	@Test
	void topLayerComesFirst() {
		// The worker is next to the bottom layer, but the top layer still goes first.
		Optional<BlockPos> next = DigOrder.next(MIN, MAX, new BlockPos(0, 0, 0), NOWHERE, pos -> true);

		assertEquals(Optional.of(new BlockPos(0, 2, 0)), next);
	}

	@Test
	void lowerLayerOnceTopIsEmpty() {
		Optional<BlockPos> next = DigOrder.next(MIN, MAX, new BlockPos(2, 5, 2), NOWHERE, pos -> pos.getY() < 2);

		assertEquals(Optional.of(new BlockPos(2, 1, 2)), next);
	}

	@Test
	void nearestWinsWithinLayer() {
		Optional<BlockPos> next = DigOrder.next(MIN, MAX, new BlockPos(2, 0, 1), NOWHERE, pos -> pos.getY() == 0);

		assertEquals(Optional.of(new BlockPos(2, 0, 1)), next);
	}

	@Test
	void tiesGoToLowerXThenZ() {
		// (1,0,0), (0,0,1), (2,0,1) and (1,0,2) are all one block from (1,0,1), which is not a candidate.
		Set<BlockPos> candidates = Set.of(new BlockPos(2, 0, 1), new BlockPos(1, 0, 2), new BlockPos(0, 0, 1), new BlockPos(1, 0, 0));

		Optional<BlockPos> next = DigOrder.next(MIN, MAX, new BlockPos(1, 0, 1), NOWHERE, candidates::contains);

		assertEquals(Optional.of(new BlockPos(0, 0, 1)), next);
	}

	@Test
	void standingOnIsNeverReturned() {
		BlockPos standingOn = new BlockPos(1, 0, 1);

		Optional<BlockPos> only = DigOrder.next(MIN, MAX, new BlockPos(1, 1, 1), standingOn, standingOn::equals);
		Optional<BlockPos> next = DigOrder.next(MIN, MAX, new BlockPos(1, 1, 1), standingOn, pos -> pos.getY() == 0);

		assertEquals(Optional.empty(), only);
		assertEquals(1, next.orElseThrow().distManhattan(standingOn));
	}

	@Test
	void emptyWhenNothingIsACandidate() {
		assertEquals(Optional.empty(), DigOrder.next(MIN, MAX, BlockPos.ZERO, NOWHERE, pos -> false));
	}

	@Test
	void reversedCornersAreNormalized() {
		BlockPos a = new BlockPos(5, -1, 3);
		BlockPos b = new BlockPos(-2, 4, -7);

		assertEquals(new BlockPos(-2, -1, -7), DigOrder.min(a, b));
		assertEquals(new BlockPos(5, 4, 3), DigOrder.max(a, b));
		assertEquals(8L * 6 * 11, DigOrder.volume(a, b));
		assertEquals(DigOrder.next(a, b, BlockPos.ZERO, NOWHERE, pos -> true), DigOrder.next(b, a, BlockPos.ZERO, NOWHERE, pos -> true));
		assertEquals(4, DigOrder.next(b, a, BlockPos.ZERO, NOWHERE, pos -> true).orElseThrow().getY());
	}
}
