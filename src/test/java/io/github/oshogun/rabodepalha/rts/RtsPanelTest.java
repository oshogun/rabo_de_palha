package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtsPanelTest {
	/** The number of buttons the command bar ships with. */
	private static final int BAR_COUNT = 5;
	private static final int[][] WINDOWS = {{1920, 1080}, {1280, 720}, {854, 480}, {640, 480}};

	private static List<Rect> parts(RtsPanel.Layout l) {
		List<Rect> parts = new ArrayList<>(List.of(l.header(), l.clear(), l.current(), l.queuedLabel(), l.more()));
		parts.addAll(l.queued());
		parts.addAll(l.remove());
		parts.addAll(l.rows());
		parts.addAll(l.slots());
		parts.removeIf(r -> r.equals(RtsPanel.EMPTY));
		return parts;
	}

	private static boolean overlaps(Rect a, Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	private static void assertFits(RtsPanel.Layout l, int guiWidth, int guiHeight, String at) {
		Rect b = l.bounds();
		assertEquals(guiWidth - RtsPanel.MARGIN, b.x() + b.w(), at + ": right-anchored");
		assertEquals(RtsPanel.MARGIN, b.y(), at + ": top-anchored");
		assertTrue(b.y() + b.h() <= RtsPanel.maxBottom(guiHeight), at + ": above the bar " + b);
		Rect bar = RtsCommandBar.bounds(BAR_COUNT, guiWidth, guiHeight);
		assertFalse(overlaps(b, bar), at + ": panel " + b + " bar " + bar);
		for (Rect part : parts(l)) {
			assertTrue(part.x() >= b.x() && part.x() + part.w() <= b.x() + b.w()
					&& part.y() >= b.y() && part.y() + part.h() <= b.y() + b.h(), at + ": " + part + " in " + b);
		}
		assertTrue(RtsPanel.MARGIN + RtsPanel.leftTextMaxWidth(guiWidth) + RtsPanel.GAP <= b.x(), at + ": left text");
	}

	@Test
	void panelStaysInsideAndClearOfTheBarAndLeftTextAtEveryScale() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				String at = window[0] + "x" + window[1] + " @" + scale;
				for (int queued : new int[] {0, 1, 5, 40}) {
					assertFits(RtsPanel.single(queued, WorkerEntity.INVENTORY_SIZE, guiWidth, guiHeight), guiWidth,
							guiHeight, at + " queued " + queued);
				}
				for (int workers : new int[] {2, 8, 16}) {
					assertFits(RtsPanel.multi(workers, guiWidth, guiHeight), guiWidth, guiHeight,
							at + " workers " + workers);
				}
			}
		}
	}

	@Test
	void widthIsCappedAndAThirdOfNarrowScreens() {
		assertEquals(RtsPanel.MAX_WIDTH, RtsPanel.width(1920));
		assertEquals(106, RtsPanel.width(320));
		assertEquals(320 - 4 - 106 - 4 - 4, RtsPanel.leftTextMaxWidth(320));
	}

	@Test
	void singleShowsEveryPartWhenThereIsRoom() {
		RtsPanel.Layout l = RtsPanel.single(3, WorkerEntity.INVENTORY_SIZE, 960, 540);
		assertEquals(RtsPanel.Mode.SINGLE, l.mode());
		assertEquals(3, l.queued().size());
		assertEquals(3, l.remove().size());
		assertEquals(0, l.hidden());
		assertEquals(RtsPanel.EMPTY, l.more());
		assertEquals(WorkerEntity.INVENTORY_SIZE, l.slots().size());
		assertTrue(l.rows().isEmpty());
		// Header, current, label and queued rows stack in order, 10px apart.
		assertEquals(l.header().y() + RtsPanel.ROW, l.current().y());
		assertEquals(l.current().y() + RtsPanel.ROW, l.queuedLabel().y());
		for (int i = 0; i < 3; i++) {
			assertEquals(l.queuedLabel().y() + (i + 1) * RtsPanel.ROW, l.queued().get(i).y());
		}
		assertTrue(l.slots().get(0).y() >= l.queued().get(2).y() + RtsPanel.ROW + RtsPanel.GAP);
		// 8 columns of 18px cells in a 160px panel.
		assertEquals(8, RtsPanel.columns(960));
		assertEquals(l.slots().get(0).y() + RtsPanel.CELL, l.slots().get(8).y());
		assertEquals(l.slots().get(0).x() + RtsPanel.CELL, l.slots().get(1).x());
	}

	@Test
	void singleWithoutQueuedTasksHasNoLabel() {
		RtsPanel.Layout l = RtsPanel.single(0, 0, 960, 540);
		assertEquals(RtsPanel.EMPTY, l.queuedLabel());
		assertTrue(l.queued().isEmpty());
		assertTrue(l.slots().isEmpty());
		assertEquals(2 * RtsPanel.PAD + 2 * RtsPanel.ROW, l.bounds().h());
	}

	@Test
	void longQueuesCollapseIntoAMoreRow() {
		RtsPanel.Layout l = RtsPanel.single(100, WorkerEntity.INVENTORY_SIZE, 640, 360);
		assertTrue(l.queued().size() > 0);
		assertEquals(100, l.queued().size() + l.hidden());
		assertTrue(l.hidden() > 0);
		assertTrue(!l.more().equals(RtsPanel.EMPTY));
		assertEquals(l.queued().get(l.queued().size() - 1).y() + RtsPanel.ROW, l.more().y());
		assertEquals(WorkerEntity.INVENTORY_SIZE, l.slots().size());
	}

	@Test
	void smallScreensKeepAQueuedRowAndAtLeastOneInventoryRow() {
		// 854x480 at scale 4: 213x120 GUI pixels, the smallest common case.
		RtsPanel.Layout l = RtsPanel.single(5, WorkerEntity.INVENTORY_SIZE, 213, 120);
		assertFalse(l.header().equals(RtsPanel.EMPTY));
		assertFalse(l.current().equals(RtsPanel.EMPTY));
		assertFalse(l.queued().isEmpty() && l.more().equals(RtsPanel.EMPTY));
		assertFalse(l.slots().isEmpty());
		assertEquals(5, l.queued().size() + l.hidden());
	}

	@Test
	void multiListsWorkersAndCollapsesTheRest() {
		RtsPanel.Layout l = RtsPanel.multi(4, 960, 540);
		assertEquals(RtsPanel.Mode.MULTI, l.mode());
		assertEquals(4, l.rows().size());
		assertEquals(0, l.hidden());
		assertTrue(l.slots().isEmpty());
		assertEquals(RtsPanel.EMPTY, l.clear());

		RtsPanel.Layout small = RtsPanel.multi(16, 213, 120);
		assertTrue(small.hidden() > 0);
		assertEquals(16, small.rows().size() + small.hidden());
		assertFalse(small.more().equals(RtsPanel.EMPTY));
	}

	@Test
	void hitTestSingle() {
		RtsPanel.Layout l = RtsPanel.single(3, WorkerEntity.INVENTORY_SIZE, 960, 540);
		Rect clear = l.clear();
		assertEquals(RtsPanel.Hit.CLEAR, RtsPanel.hitTest(l, clear.x(), clear.y()));
		assertEquals(RtsPanel.Hit.CLEAR, RtsPanel.hitTest(l, clear.x() + clear.w() - 0.01, clear.y() + clear.h() - 0.01));
		for (int i = 0; i < 3; i++) {
			Rect r = l.remove().get(i);
			assertEquals(new RtsPanel.Hit.Remove(i), RtsPanel.hitTest(l, r.x() + r.w() / 2.0, r.y() + r.h() / 2.0));
			// The rest of the row is not a button.
			Rect row = l.queued().get(i);
			assertEquals(RtsPanel.Hit.BODY, RtsPanel.hitTest(l, row.x() + 1, row.y() + 1));
		}
		Rect slot = l.slots().get(0);
		assertEquals(RtsPanel.Hit.BODY, RtsPanel.hitTest(l, slot.x() + 1, slot.y() + 1));
		assertEquals(RtsPanel.Hit.BODY, RtsPanel.hitTest(l, l.header().x() + 1, l.header().y() + 1));
		Rect b = l.bounds();
		assertEquals(RtsPanel.Hit.NONE, RtsPanel.hitTest(l, b.x() - 0.01, b.y() + 1));
		assertEquals(RtsPanel.Hit.NONE, RtsPanel.hitTest(l, b.x() + 1, b.y() + b.h()));
		assertEquals(RtsPanel.Hit.NONE, RtsPanel.hitTest(l, 480, 270));
	}

	@Test
	void hitTestMulti() {
		RtsPanel.Layout l = RtsPanel.multi(16, 213, 120);
		for (int i = 0; i < l.rows().size(); i++) {
			Rect r = l.rows().get(i);
			assertEquals(new RtsPanel.Hit.Row(i), RtsPanel.hitTest(l, r.x() + 1, r.y() + r.h() / 2.0));
		}
		assertEquals(RtsPanel.Hit.BODY, RtsPanel.hitTest(l, l.more().x() + 1, l.more().y() + 1));
		assertEquals(RtsPanel.Hit.NONE, RtsPanel.hitTest(l, 10, 10));
	}
}
