package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsAreaDragTest {
	/** A drag from (0,64,0) to (3,64,2), released into the depth step at depth 1. */
	private static RtsAreaDrag released() {
		RtsAreaDrag drag = new RtsAreaDrag();
		drag.press(0, 64, 0);
		drag.drag(3, 64, 2);
		assertTrue(drag.release());
		return drag;
	}

	@Test
	void pressDragReleaseConfirm() {
		RtsAreaDrag drag = new RtsAreaDrag();
		assertEquals(RtsAreaDrag.Phase.IDLE, drag.phase());
		assertNull(drag.area());

		drag.press(5, 64, 5);
		assertEquals(RtsAreaDrag.Phase.DRAGGING, drag.phase());
		assertEquals(new RtsDigArea(5, 64, 5, 5, 64, 5), drag.area());

		drag.drag(1, 66, 8);
		assertEquals(new RtsDigArea(1, 64, 5, 5, 66, 8), drag.area());

		assertTrue(drag.release());
		assertEquals(RtsAreaDrag.Phase.DEPTH, drag.phase());
		assertEquals(3, drag.depth());
		assertEquals(new RtsDigArea(1, 64, 5, 5, 66, 8), drag.area());

		assertEquals(new RtsDigArea(1, 64, 5, 5, 66, 8), drag.confirm());
		assertEquals(RtsAreaDrag.Phase.IDLE, drag.phase());
		assertNull(drag.area());
	}

	@Test
	void scrollDownIncreasesDepth() {
		RtsAreaDrag drag = released();
		assertEquals(1, drag.depth());

		assertTrue(drag.scroll(-1));
		assertEquals(2, drag.depth());
		assertTrue(drag.scroll(-3));
		assertEquals(5, drag.depth());

		RtsDigArea area = drag.area();
		assertEquals(64, area.maxY());
		assertEquals(60, area.minY());
	}

	@Test
	void scrollUpDecreasesDepth() {
		RtsAreaDrag drag = released();
		drag.scroll(-4);
		assertEquals(5, drag.depth());

		assertTrue(drag.scroll(1));
		assertEquals(4, drag.depth());
		assertEquals(4, drag.area().sizeY());
	}

	@Test
	void scrollUpClampedAtOne() {
		RtsAreaDrag drag = released();
		drag.scroll(-1);
		assertEquals(2, drag.depth());

		assertTrue(drag.scroll(1));
		assertTrue(drag.scroll(1));
		assertTrue(drag.scroll(1));
		assertEquals(1, drag.depth());
		assertEquals(1, drag.area().sizeY());
		assertEquals(64, drag.area().maxY());
	}

	@Test
	void scrollDownClampedAtMaxDepth() {
		RtsAreaDrag drag = released();

		assertTrue(drag.scroll(-1000));
		assertEquals(RtsDigArea.MAX_DEPTH, drag.depth());
	}

	@Test
	void scrollIgnoredOutsideDepth() {
		RtsAreaDrag drag = new RtsAreaDrag();
		assertFalse(drag.scroll(-1));
		assertFalse(drag.scroll(1));
		assertEquals(RtsAreaDrag.Phase.IDLE, drag.phase());
		assertNull(drag.area());

		drag.press(0, 64, 0);
		drag.drag(2, 60, 2);
		RtsDigArea before = drag.area();
		assertFalse(drag.scroll(-1));
		assertFalse(drag.scroll(1));
		assertEquals(RtsAreaDrag.Phase.DRAGGING, drag.phase());
		assertEquals(before, drag.area());
	}

	@Test
	void cancelFromDragging() {
		RtsAreaDrag drag = new RtsAreaDrag();
		drag.press(0, 64, 0);

		assertTrue(drag.cancel());
		assertEquals(RtsAreaDrag.Phase.IDLE, drag.phase());
		assertNull(drag.area());
		assertFalse(drag.release());
	}

	@Test
	void cancelFromDepth() {
		RtsAreaDrag drag = released();

		assertTrue(drag.cancel());
		assertEquals(RtsAreaDrag.Phase.IDLE, drag.phase());
		assertNull(drag.confirm());
	}

	@Test
	void cancelInIdleDoesNothing() {
		assertFalse(new RtsAreaDrag().cancel());
	}

	@Test
	void confirmOnlyInDepth() {
		RtsAreaDrag drag = new RtsAreaDrag();
		assertNull(drag.confirm());

		drag.press(0, 64, 0);
		assertNull(drag.confirm());
		assertEquals(RtsAreaDrag.Phase.DRAGGING, drag.phase());

		drag.release();
		assertEquals(new RtsDigArea(0, 64, 0, 0, 64, 0), drag.confirm());
		assertNull(drag.confirm());
	}

	@Test
	void dragAndPressIgnoredOutsideTheirPhase() {
		RtsAreaDrag drag = released();
		RtsDigArea before = drag.area();

		drag.press(50, 50, 50);
		drag.drag(40, 40, 40);
		assertEquals(before, drag.area());
	}
}
