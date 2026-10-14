package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtsContainerPanelTest {
	/** The number of buttons the command bar ships with, plus Deposit and Withdraw. */
	private static final int BAR_COUNT = 7;
	private static final int[][] WINDOWS = {{1920, 1080}, {1280, 720}, {854, 480}, {640, 480}};

	private static List<Rect> parts(RtsContainerPanel.Layout l) {
		List<Rect> parts = new ArrayList<>(List.of(l.header(), l.close(), l.minus(), l.count(), l.plus(), l.confirm()));
		parts.addAll(l.items());
		parts.removeIf(r -> r.w() == 0 || r.h() == 0);
		return parts;
	}

	private static boolean overlaps(Rect a, Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	@Test
	void viewStaysInsideAndClearOfThePanelAndTheBarAtEveryScale() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				String at = window[0] + "x" + window[1] + " @" + scale;
				Rect bar = RtsCommandBar.bounds(BAR_COUNT, guiWidth, guiHeight);
				for (int entries : new int[] {0, 1, 9, 64}) {
					RtsContainerPanel.Layout l = RtsContainerPanel.layout(guiWidth, guiHeight, entries);
					Rect b = l.bounds();
					String where = at + " entries " + entries + " " + b;
					assertTrue(b.y() >= RtsPanel.MARGIN, where + ": below the top");
					assertTrue(b.y() + b.h() <= RtsPanel.maxBottom(guiHeight), where + ": above the bar");
					assertFalse(overlaps(b, bar), where + ": bar " + bar);
					for (int workers : new int[] {1, 16}) {
						RtsPanel.Layout panel = workers == 1
								? RtsPanel.single(5, WorkerEntity.INVENTORY_SIZE, guiWidth, guiHeight)
								: RtsPanel.multi(workers, 40, guiWidth, guiHeight);
						assertFalse(overlaps(b, panel.bounds()), where + ": panel " + panel.bounds());
					}
					for (Rect part : parts(l)) {
						assertTrue(part.x() >= b.x() && part.x() + part.w() <= b.x() + b.w()
								&& part.y() >= b.y() && part.y() + part.h() <= b.y() + b.h(), where + ": " + part);
					}
					assertEquals(entries, l.items().size() + l.hidden(), where);
					// The controls never overlap each other.
					List<Rect> controls = List.of(l.minus(), l.count(), l.plus(), l.confirm());
					for (int i = 0; i < controls.size(); i++) {
						for (int j = i + 1; j < controls.size(); j++) {
							assertFalse(overlaps(controls.get(i), controls.get(j)), where + ": controls " + i + "/" + j);
						}
					}
				}
			}
		}
	}

	@Test
	void layoutStacksHeaderGridAndControls() {
		RtsContainerPanel.Layout l = RtsContainerPanel.layout(960, 540, 10);
		assertEquals(RtsPanel.MARGIN, l.bounds().x());
		assertEquals(RtsContainerPanel.MAX_WIDTH, l.bounds().w());
		assertEquals(RtsPanel.maxBottom(540), l.bounds().y() + l.bounds().h());
		assertEquals(10, l.items().size());
		assertEquals(0, l.hidden());
		assertEquals(l.header().y() + RtsPanel.ROW + RtsPanel.GAP, l.items().get(0).y());
		assertEquals(l.items().get(0).x() + RtsPanel.CELL, l.items().get(1).x());
		assertEquals(l.items().get(0).y() + RtsPanel.CELL, l.items().get(8).y());
		assertEquals(l.items().get(8).y() + RtsPanel.CELL + RtsPanel.GAP, l.minus().y());
		assertEquals(l.header().x() + l.header().w(), l.close().x() + l.close().w());
		assertEquals(l.minus().x() + l.minus().w(), l.count().x());
		assertEquals(l.count().x() + l.count().w(), l.plus().x());
		assertEquals(l.header().x() + l.header().w(), l.confirm().x() + l.confirm().w());
	}

	@Test
	void countLabelFitsTheEachText() {
		for (int[] gui : new int[][] {{960, 540}, {640, 360}, {427, 240}, {213, 120}}) {
			RtsContainerPanel.Layout l = RtsContainerPanel.layout(gui[0], gui[1], 3);
			assertTrue(l.count().w() >= RtsContainerPanel.COUNT_WIDTH, gui[0] + "x" + gui[1] + ": " + l.count());
		}
	}

	@Test
	void emptyContainersHaveNoGrid() {
		RtsContainerPanel.Layout l = RtsContainerPanel.layout(960, 540, 0);
		assertTrue(l.items().isEmpty());
		assertEquals(0, l.hidden());
		assertEquals(2 * RtsPanel.PAD + 2 * RtsPanel.ROW + RtsPanel.GAP, l.bounds().h());
	}

	@Test
	void largeContentsAreCut() {
		RtsContainerPanel.Layout l = RtsContainerPanel.layout(213, 120, 64);
		assertFalse(l.items().isEmpty());
		assertTrue(l.hidden() > 0);
		assertEquals(64, l.items().size() + l.hidden());
	}

	@Test
	void hitTest() {
		RtsContainerPanel.Layout l = RtsContainerPanel.layout(960, 540, 10);
		for (int i = 0; i < l.items().size(); i++) {
			Rect r = l.items().get(i);
			assertEquals(new RtsContainerPanel.Hit.Item(i),
					RtsContainerPanel.hitTest(l, r.x() + r.w() / 2.0, r.y() + r.h() / 2.0));
		}
		assertEquals(RtsContainerPanel.Hit.MINUS, center(l, l.minus()));
		assertEquals(RtsContainerPanel.Hit.PLUS, center(l, l.plus()));
		assertEquals(RtsContainerPanel.Hit.CONFIRM, center(l, l.confirm()));
		assertEquals(RtsContainerPanel.Hit.CLOSE, center(l, l.close()));
		assertEquals(RtsContainerPanel.Hit.BODY, center(l, l.count()));
		assertEquals(RtsContainerPanel.Hit.BODY, RtsContainerPanel.hitTest(l, l.header().x() + 1, l.header().y() + 1));
		Rect b = l.bounds();
		assertEquals(RtsContainerPanel.Hit.NONE, RtsContainerPanel.hitTest(l, b.x() + b.w(), b.y() + 1));
		assertEquals(RtsContainerPanel.Hit.NONE, RtsContainerPanel.hitTest(l, b.x() + 1, b.y() - 0.01));
		assertEquals(RtsContainerPanel.Hit.NONE, RtsContainerPanel.hitTest(l, 480, 10));
	}

	private static RtsContainerPanel.Hit center(RtsContainerPanel.Layout l, Rect r) {
		return RtsContainerPanel.hitTest(l, r.x() + r.w() / 2.0, r.y() + r.h() / 2.0);
	}
}
