package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.oshogun.rabodepalha.rts.RtsOrderHint.Hint;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint.HoverKind;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint.Target;

class RtsOrderHintTest {
	private static final double MAX_SQR = RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE;

	/** A solid block hit close by, with a block in hand: every kind is valid. */
	private static Target solid() {
		return new Target(false, true, false, true, 16.0);
	}

	@Test
	void moveIsValid() {
		Hint hint = RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, solid());

		assertEquals(new Hint("rabodepalha.rts.hint.move", true, RtsOrderKind.MOVE), hint);
	}

	@Test
	void mineIsValid() {
		Hint hint = RtsOrderHint.evaluate(2, RtsOrderKind.MINE, solid());

		assertEquals(new Hint("rabodepalha.rts.hint.mine", true, RtsOrderKind.MINE), hint);
	}

	@Test
	void placeIsValid() {
		Hint hint = RtsOrderHint.evaluate(3, RtsOrderKind.PLACE, solid());

		assertEquals(new Hint("rabodepalha.rts.hint.place", true, RtsOrderKind.PLACE), hint);
	}

	@Test
	void nothingSelectedIsRejected() {
		Hint hint = RtsOrderHint.evaluate(0, RtsOrderKind.MOVE, solid());

		assertEquals(new Hint("rabodepalha.rts.order.nothing_selected", false, RtsOrderKind.MOVE), hint);
	}

	@Test
	void noBlockHitIsRejected() {
		Hint hint = RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, new Target(false, false, false, true, 0.0));

		assertEquals(new Hint("rabodepalha.rts.order.no_target", false, RtsOrderKind.MOVE), hint);
	}

	@Test
	void miningAirIsRejected() {
		Hint hint = RtsOrderHint.evaluate(1, RtsOrderKind.MINE, new Target(false, true, true, true, 16.0));

		assertEquals(new Hint("rabodepalha.rts.order.air", false, RtsOrderKind.MINE), hint);
	}

	@Test
	void airOnlyMattersForMine() {
		Target air = new Target(false, true, true, true, 16.0);

		assertTrue(RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, air).valid());
		assertTrue(RtsOrderHint.evaluate(1, RtsOrderKind.PLACE, air).valid());
	}

	@Test
	void placeWithoutBlockIsRejected() {
		Hint hint = RtsOrderHint.evaluate(1, RtsOrderKind.PLACE, new Target(false, true, false, false, 16.0));

		assertEquals(new Hint("rabodepalha.rts.order.no_block", false, RtsOrderKind.PLACE), hint);
	}

	@Test
	void emptyHandOnlyMattersForPlace() {
		Target emptyHand = new Target(false, true, false, false, 16.0);

		assertTrue(RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, emptyHand).valid());
		assertTrue(RtsOrderHint.evaluate(1, RtsOrderKind.MINE, emptyHand).valid());
	}

	@Test
	void justBeyondLimitIsRejected() {
		Target far = new Target(false, true, false, true, Math.nextUp(MAX_SQR));

		for (Hint hint : List.of(
				RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, far),
				RtsOrderHint.evaluate(1, RtsOrderKind.MINE, far),
				RtsOrderHint.evaluate(1, RtsOrderKind.PLACE, far))) {
			assertFalse(hint.valid());
			assertEquals("rabodepalha.rts.order.target_too_far", hint.key());
		}
	}

	@Test
	void exactlyAtLimitIsValid() {
		Hint hint = RtsOrderHint.evaluate(1, RtsOrderKind.MOVE, new Target(false, true, false, true, MAX_SQR));

		assertTrue(hint.valid());
	}

	/** A lever or button hit close by. */
	private static Target switchBlock() {
		return new Target(false, true, false, false, 16.0, true, true);
	}

	/** A door or gate hit close by: usable, but not a switch. */
	private static Target door() {
		return new Target(false, true, false, false, 16.0, true, false);
	}

	@Test
	void rightClickUsesSwitches() {
		assertEquals(RtsOrderKind.USE, RtsOrderHint.rightClickKind(switchBlock()));
	}

	@Test
	void rightClickMovesElsewhere() {
		assertEquals(RtsOrderKind.MOVE, RtsOrderHint.rightClickKind(solid()));
		assertEquals(RtsOrderKind.MOVE, RtsOrderHint.rightClickKind(door()));
		assertEquals(RtsOrderKind.MOVE, RtsOrderHint.rightClickKind(new Target(false, false, false, false, 0.0)));
		assertEquals(RtsOrderKind.MOVE,
				RtsOrderHint.rightClickKind(new Target(true, true, false, false, 16.0, true, true)), "worker hit");
	}

	@Test
	void useOnSwitchIsValid() {
		assertEquals(new Hint("rabodepalha.rts.hint.use", true, RtsOrderKind.USE),
				RtsOrderHint.evaluate(1, RtsOrderKind.USE, switchBlock()));
		assertTrue(RtsOrderHint.evaluate(1, RtsOrderKind.USE, door()).valid());
	}

	@Test
	void useOnUnusableBlockOrAirIsRejected() {
		Hint notUsable = new Hint("rabodepalha.rts.order.not_usable", false, RtsOrderKind.USE);

		assertEquals(notUsable, RtsOrderHint.evaluate(1, RtsOrderKind.USE, solid()));
		assertEquals(notUsable, RtsOrderHint.evaluate(1, RtsOrderKind.USE, new Target(false, true, true, false, 16.0)));
	}

	@Test
	void useRejectionOrder() {
		Target farSwitch = new Target(false, true, false, false, Math.nextUp(MAX_SQR), true, true);
		Target farStone = new Target(false, true, false, false, Math.nextUp(MAX_SQR));

		assertEquals("rabodepalha.rts.order.target_too_far", RtsOrderHint.evaluate(1, RtsOrderKind.USE, farSwitch).key());
		assertEquals("rabodepalha.rts.order.not_usable", RtsOrderHint.evaluate(1, RtsOrderKind.USE, farStone).key());
		assertEquals("rabodepalha.rts.order.nothing_selected",
				RtsOrderHint.evaluate(0, RtsOrderKind.USE, switchBlock()).key());
		assertEquals("rabodepalha.rts.order.no_target",
				RtsOrderHint.evaluate(1, RtsOrderKind.USE, new Target(false, false, false, false, 0.0)).key());
	}

	@Test
	void usableFlagsOnlyMatterForUse() {
		for (RtsOrderKind kind : List.of(RtsOrderKind.MOVE, RtsOrderKind.MINE, RtsOrderKind.PLACE)) {
			assertEquals(RtsOrderHint.evaluate(1, kind, new Target(false, true, false, true, 16.0)),
					RtsOrderHint.evaluate(1, kind, new Target(false, true, false, true, 16.0, true, true)), kind.name());
		}
	}

	@Test
	void nothingSelectedBeatsOtherRejections() {
		Target everythingWrong = new Target(false, false, true, false, MAX_SQR * 4);

		for (RtsOrderKind kind : RtsOrderKind.values()) {
			Hint hint = RtsOrderHint.evaluate(0, kind, everythingWrong);
			assertFalse(hint.valid());
			assertEquals("rabodepalha.rts.order.nothing_selected", hint.key());
			assertEquals(kind, hint.kind());
		}
	}

	@Test
	void workerInFrontDoesNotChangeHint() {
		List<Target> targets = List.of(
				solid(),
				new Target(false, false, false, true, 0.0),
				new Target(false, true, true, true, 16.0),
				new Target(false, true, false, false, 16.0),
				new Target(false, true, false, true, Math.nextUp(MAX_SQR)));

		for (Target noWorker : targets) {
			Target withWorker = new Target(true, noWorker.blockHit(), noWorker.blockIsAir(), noWorker.pickedBlock(),
					noWorker.targetDistSqr());
			for (int count : new int[] {0, 1}) {
				for (RtsOrderKind kind : RtsOrderKind.values()) {
					assertEquals(RtsOrderHint.evaluate(count, kind, noWorker),
							RtsOrderHint.evaluate(count, kind, withWorker));
				}
			}
			for (boolean room : new boolean[] {false, true}) {
				assertEquals(RtsOrderHint.evaluateSpawn(noWorker, room), RtsOrderHint.evaluateSpawn(withWorker, room));
			}
		}
	}

	@Test
	void spawnIsValidWithoutSelection() {
		Hint hint = RtsOrderHint.evaluateSpawn(solid(), true);

		assertEquals(new Hint("rabodepalha.rts.hint.spawn", true, null), hint);
	}

	@Test
	void spawnWithoutBlockHitIsRejected() {
		Hint hint = RtsOrderHint.evaluateSpawn(new Target(false, false, false, true, 0.0), true);

		assertEquals(new Hint("rabodepalha.rts.order.no_target", false, null), hint);
	}

	@Test
	void spawnOnAirIsRejected() {
		Hint hint = RtsOrderHint.evaluateSpawn(new Target(false, true, true, true, 16.0), true);

		assertEquals(new Hint("rabodepalha.rts.order.no_target", false, null), hint);
	}

	@Test
	void spawnBeyondLimitIsRejected() {
		Hint hint = RtsOrderHint.evaluateSpawn(new Target(false, true, false, true, Math.nextUp(MAX_SQR)), true);

		assertEquals(new Hint("rabodepalha.rts.order.target_too_far", false, null), hint);
	}

	@Test
	void spawnAtLimitIsValid() {
		assertTrue(RtsOrderHint.evaluateSpawn(new Target(false, true, false, true, MAX_SQR), true).valid());
	}

	@Test
	void spawnWithoutRoomIsRejected() {
		Hint hint = RtsOrderHint.evaluateSpawn(solid(), false);

		assertEquals(new Hint("rabodepalha.rts.order.no_room", false, null), hint);
	}

	@Test
	void spawnTooFarBeatsNoRoom() {
		Hint hint = RtsOrderHint.evaluateSpawn(new Target(false, true, false, true, Math.nextUp(MAX_SQR)), false);

		assertEquals("rabodepalha.rts.order.target_too_far", hint.key());
	}

	@Test
	void spawnIgnoresHeldItem() {
		assertTrue(RtsOrderHint.evaluateSpawn(new Target(false, true, false, false, 16.0), true).valid());
	}

	@Test
	void containerIsValid() {
		assertEquals(new Hint("rabodepalha.rts.hint.deposit", true, null),
				RtsOrderHint.evaluateContainer(1, true, "rabodepalha.rts.hint.deposit"));
		assertEquals(new Hint("rabodepalha.rts.hint.withdraw", true, null),
				RtsOrderHint.evaluateContainer(3, true, "rabodepalha.rts.hint.withdraw"));
	}

	@Test
	void containerWithoutSelectionIsRejected() {
		assertEquals(new Hint("rabodepalha.rts.order.nothing_selected", false, null),
				RtsOrderHint.evaluateContainer(0, true, "rabodepalha.rts.hint.deposit"));
	}

	@Test
	void nonContainerIsRejected() {
		assertEquals(new Hint("rabodepalha.rts.order.not_container", false, null),
				RtsOrderHint.evaluateContainer(1, false, "rabodepalha.rts.hint.withdraw"));
	}

	@Test
	void containerNothingSelectedBeatsNotContainer() {
		assertEquals("rabodepalha.rts.order.nothing_selected",
				RtsOrderHint.evaluateContainer(0, false, "rabodepalha.rts.hint.deposit").key());
	}

	@Test
	void workerBeatsBlockForHover() {
		assertEquals(HoverKind.WORKER, RtsOrderHint.hoverKind(new Target(true, true, false, false, 16.0)));
	}

	@Test
	void blockOnlyHoversBlock() {
		assertEquals(HoverKind.BLOCK, RtsOrderHint.hoverKind(solid()));
	}

	@Test
	void nothingUnderCursorHoversNothing() {
		assertEquals(HoverKind.NONE, RtsOrderHint.hoverKind(new Target(false, false, false, false, 0.0)));
	}
}
