package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtsCommandBarTest {
	/**
	 * The number of commands the bar ships with: Move, Mine, Place, Dig area, Stop, Spawn worker, Deposit, Withdraw
	 * and Build. The single-row methods still lay out this many.
	 */
	private static final int COUNT = 9;
	/** The paged bar's largest page today, Orders, and its global buttons, Stop. */
	private static final int PAGE = 5;
	private static final int GLOBALS = 1;
	/** Tab widths as the font gives them, roughly: "Orders" and "Haul and build" plus padding. */
	private static final int[] TABS = {41, 75};
	/** The bottom of the HUD's four top-left lines: a 4px margin plus four 11px lines (font height + 2). */
	private static final int HUD_BOTTOM = 4 + 4 * 11;

	private static final int[][] WINDOWS = {{1920, 1080}, {1280, 720}, {854, 480}};

	@Test
	void layoutIsCentredAtTheBottomAtEveryScale() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
				String at = window[0] + "x" + window[1] + " @" + scale;

				assertEquals(COUNT, rects.size(), at);
				RtsCommandBar.Rect first = rects.get(0);
				RtsCommandBar.Rect last = rects.get(COUNT - 1);
				int left = first.x();
				int right = guiWidth - (last.x() + last.w());
				assertTrue(Math.abs(left - right) <= 1, at + ": centred, left " + left + " right " + right);
				assertEquals(RtsCommandBar.width(COUNT), last.x() + last.w() - first.x(), at);
				for (int i = 0; i < COUNT; i++) {
					RtsCommandBar.Rect r = rects.get(i);
					assertEquals(RtsCommandBar.BUTTON, r.w(), at);
					assertEquals(RtsCommandBar.BUTTON, r.h(), at);
					assertEquals(guiHeight - RtsCommandBar.BOTTOM_MARGIN, r.y() + r.h(), at);
					if (i > 0) {
						RtsCommandBar.Rect prev = rects.get(i - 1);
						assertEquals(RtsCommandBar.GAP, r.x() - (prev.x() + prev.w()), at);
					}
				}
			}
		}
	}

	@Test
	void emptyBarHasNoButtons() {
		assertEquals(0, RtsCommandBar.width(0));
		assertTrue(RtsCommandBar.layout(0, 400, 300).isEmpty());
		assertEquals(-1, RtsCommandBar.hitTest(0, 400, 300, 200, 290));
	}

	@Test
	void hitTestFindsButtonsAtCentresAndEdges() {
		int guiWidth = 427;
		int guiHeight = 240;
		List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
		for (int i = 0; i < COUNT; i++) {
			RtsCommandBar.Rect r = rects.get(i);
			assertEquals(i, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, r.x() + r.w() / 2.0, r.y() + r.h() / 2.0));
			// Left and top edges are inside.
			assertEquals(i, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, r.x(), r.y()));
			assertEquals(i, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, r.x() + r.w() - 0.01, r.y() + r.h() - 0.01));
			// Right and bottom edges are outside.
			assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, r.x() + r.w() / 2.0, r.y() + r.h()));
			assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, r.x() - 0.01, r.y() - 0.01));
		}
	}

	@Test
	void hitTestMissesGapsAndOutsideTheBar() {
		int guiWidth = 640;
		int guiHeight = 360;
		List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
		RtsCommandBar.Rect first = rects.get(0);
		RtsCommandBar.Rect last = rects.get(COUNT - 1);
		double midY = first.y() + first.h() / 2.0;

		for (int i = 0; i < COUNT - 1; i++) {
			RtsCommandBar.Rect r = rects.get(i);
			double gapX = r.x() + r.w() + RtsCommandBar.GAP / 2.0;
			assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, gapX, midY), "gap after " + i);
		}
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, first.x() - 1, midY));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, last.x() + last.w() + 1, midY));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, first.x() + 1, first.y() - 1));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, first.x() + 1, first.y() + first.h() + 1));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, -5, -5));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, guiWidth + 10, guiHeight + 10));
		assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, guiWidth / 2.0, guiHeight / 2.0));
	}

	@Test
	void barContainsGapsButNotOutside() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				String at = window[0] + "x" + window[1] + " @" + scale;
				List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
				RtsCommandBar.Rect first = rects.get(0);
				RtsCommandBar.Rect last = rects.get(COUNT - 1);
				double midY = first.y() + first.h() / 2.0;

				for (int i = 0; i < COUNT; i++) {
					RtsCommandBar.Rect r = rects.get(i);
					assertTrue(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, r.x() + r.w() / 2.0, midY), at + " button " + i);
					if (i < COUNT - 1) {
						double gapX = r.x() + r.w() + RtsCommandBar.GAP / 2.0;
						assertEquals(-1, RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight, gapX, midY), at + " gap after " + i);
						assertTrue(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, gapX, midY), at + " gap after " + i);
						assertTrue(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, r.x() + r.w(), first.y()), at + " gap edge " + i);
					}
				}
				assertFalse(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, first.x() - 0.01, midY), at);
				assertFalse(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, last.x() + last.w(), midY), at);
				assertFalse(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, first.x() + 1, first.y() - 0.01), at);
				assertFalse(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, first.x() + 1, first.y() + first.h()), at);
				assertFalse(RtsCommandBar.contains(COUNT, guiWidth, guiHeight, guiWidth / 2.0, guiHeight / 2.0), at);
			}
		}
		assertFalse(RtsCommandBar.contains(0, 400, 300, 200, 290));
	}

	@Test
	void windowPixelsMapToTheSameButtonAtEveryScale() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
				for (int i = 0; i < COUNT; i++) {
					RtsCommandBar.Rect r = rects.get(i);
					double windowX = (r.x() + r.w() / 2.0) * scale;
					double windowY = (r.y() + r.h() / 2.0) * scale;
					int hit = RtsCommandBar.hitTest(COUNT, guiWidth, guiHeight,
						RtsCommandBar.toGui(windowX, scale), RtsCommandBar.toGui(windowY, scale));
					assertEquals(i, hit, window[0] + "x" + window[1] + " @" + scale);
				}
			}
		}
	}

	@Test
	void toGuiDividesByScale() {
		assertEquals(100.0, RtsCommandBar.toGui(200, 2));
		assertEquals(33.0, RtsCommandBar.toGui(99, 3));
		assertEquals(12.5, RtsCommandBar.toGui(50, 4));
	}

	@Test
	void barFitsOnTheSmallestScreenBelowTheHud() {
		int guiWidth = 320;
		int guiHeight = 240;
		List<RtsCommandBar.Rect> rects = RtsCommandBar.layout(COUNT, guiWidth, guiHeight);
		for (RtsCommandBar.Rect r : rects) {
			assertTrue(r.x() >= 0 && r.x() + r.w() <= guiWidth, "inside horizontally: " + r);
			assertTrue(r.y() >= 0 && r.y() + r.h() <= guiHeight, "inside vertically: " + r);
			assertTrue(r.y() > HUD_BOTTOM, "below the HUD lines: " + r);
		}
	}

	@Test
	void pagedLayoutIsCentredWithAGapBeforeTheGlobals() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				String at = window[0] + "x" + window[1] + " @" + scale;
				for (int page = 1; page <= RtsCommandPage.MAX_SLOTS; page++) {
					List<RtsCommandBar.Rect> rects = RtsCommandBar.pagedLayout(page, GLOBALS, guiWidth, guiHeight);
					assertEquals(page + GLOBALS, rects.size(), at);
					RtsCommandBar.Rect first = rects.get(0);
					RtsCommandBar.Rect last = rects.get(rects.size() - 1);
					int left = first.x();
					int right = guiWidth - (last.x() + last.w());
					assertTrue(Math.abs(left - right) <= 1, at + ": centred, left " + left + " right " + right);
					assertEquals(RtsCommandBar.pagedWidth(page, GLOBALS), last.x() + last.w() - first.x(), at);
					for (int i = 1; i < rects.size(); i++) {
						RtsCommandBar.Rect prev = rects.get(i - 1);
						int gap = i == page ? RtsCommandBar.GROUP_GAP : RtsCommandBar.GAP;
						assertEquals(gap, rects.get(i).x() - (prev.x() + prev.w()), at + " before " + i);
					}
					for (RtsCommandBar.Rect r : rects) {
						assertEquals(RtsCommandBar.BUTTON, r.w(), at);
						assertEquals(guiHeight - RtsCommandBar.BOTTOM_MARGIN, r.y() + r.h(), at);
					}
				}
			}
		}
		// Without globals, or without page buttons, there is no group gap.
		assertEquals(RtsCommandBar.width(3), RtsCommandBar.pagedWidth(3, 0));
		assertEquals(RtsCommandBar.width(1), RtsCommandBar.pagedWidth(0, 1));
		assertTrue(RtsCommandBar.pagedLayout(0, 0, 400, 300).isEmpty());
	}

	@Test
	void pagedHitTestFindsPageAndGlobalButtons() {
		int guiWidth = 427;
		int guiHeight = 240;
		List<RtsCommandBar.Rect> rects = RtsCommandBar.pagedLayout(PAGE, GLOBALS, guiWidth, guiHeight);
		for (int i = 0; i < rects.size(); i++) {
			RtsCommandBar.Rect r = rects.get(i);
			RtsCommandBar.Hit expected = i < PAGE ? new RtsCommandBar.Hit(RtsCommandBar.Hit.Kind.PAGE, i)
					: new RtsCommandBar.Hit(RtsCommandBar.Hit.Kind.GLOBAL, i - PAGE);
			assertEquals(expected, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight, r.x() + r.w() / 2.0,
					r.y() + r.h() / 2.0));
			assertEquals(expected, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight, r.x(), r.y()));
			assertEquals(expected, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight, r.x() + r.w() - 0.01,
					r.y() + r.h() - 0.01));
			assertEquals(RtsCommandBar.Hit.NONE, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight,
					r.x() + r.w() / 2.0, r.y() + r.h()));
		}
		double midY = rects.get(0).y() + RtsCommandBar.BUTTON / 2.0;
		for (int i = 0; i < rects.size() - 1; i++) {
			RtsCommandBar.Rect r = rects.get(i);
			double gapX = r.x() + r.w() + 1;
			assertEquals(RtsCommandBar.Hit.NONE, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight, gapX,
					midY), "gap after " + i);
			// Gaps are still on the bar, so a click there is consumed.
			assertTrue(RtsCommandBar.pagedContains(PAGE, GLOBALS, TABS, guiWidth, guiHeight, gapX, midY), "gap " + i);
		}
		assertEquals(RtsCommandBar.Hit.NONE, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight,
				guiWidth / 2.0, guiHeight / 2.0));
		assertEquals(RtsCommandBar.Hit.NONE, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight,
				rects.get(0).x() - 1, midY));
	}

	@Test
	void tabsSitAboveTheBarWithoutOverlapping() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				String at = window[0] + "x" + window[1] + " @" + scale;
				List<RtsCommandBar.Rect> tabs = RtsCommandBar.tabsLayout(TABS, guiWidth, guiHeight);
				RtsCommandBar.Rect bar = RtsCommandBar.pagedBounds(PAGE, GLOBALS, guiWidth, guiHeight);
				assertEquals(TABS.length, tabs.size(), at);
				RtsCommandBar.Rect first = tabs.get(0);
				RtsCommandBar.Rect last = tabs.get(tabs.size() - 1);
				int left = first.x();
				int right = guiWidth - (last.x() + last.w());
				assertTrue(Math.abs(left - right) <= 1, at + ": centred, left " + left + " right " + right);
				for (int i = 0; i < tabs.size(); i++) {
					RtsCommandBar.Rect tab = tabs.get(i);
					assertEquals(TABS[i], tab.w(), at);
					assertEquals(RtsCommandBar.TAB_HEIGHT, tab.h(), at);
					assertEquals(bar.y() - RtsCommandBar.TAB_GAP, tab.y() + tab.h(), at + ": just above the bar");
					assertEquals(RtsCommandBar.top(tabs.size(), guiHeight), tab.y(), at);
					if (i > 0) {
						RtsCommandBar.Rect prev = tabs.get(i - 1);
						assertEquals(RtsCommandBar.GAP, tab.x() - (prev.x() + prev.w()), at);
					}
				}
				assertEquals(RtsCommandBar.maxTop(guiHeight), RtsCommandBar.top(TABS.length, guiHeight), at);
			}
		}
	}

	@Test
	void aSinglePageHasNoTabs() {
		assertTrue(RtsCommandBar.tabsLayout(new int[] {40}, 400, 300).isEmpty());
		assertTrue(RtsCommandBar.tabsLayout(new int[0], 400, 300).isEmpty());
		assertEquals(300 - RtsCommandBar.BOTTOM_MARGIN - RtsCommandBar.BUTTON, RtsCommandBar.top(1, 300));
		assertEquals(-1, RtsCommandBar.tabHitTest(new int[] {40}, 400, 300, 200, 300 - 30));
	}

	@Test
	void tabHitTestFindsTabsAtEdgesAndMissesGaps() {
		int guiWidth = 640;
		int guiHeight = 360;
		List<RtsCommandBar.Rect> tabs = RtsCommandBar.tabsLayout(TABS, guiWidth, guiHeight);
		for (int i = 0; i < tabs.size(); i++) {
			RtsCommandBar.Rect t = tabs.get(i);
			assertEquals(i, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, t.x() + t.w() / 2.0, t.y() + t.h() / 2.0));
			assertEquals(i, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, t.x(), t.y()));
			assertEquals(i, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, t.x() + t.w() - 0.01, t.y() + t.h() - 0.01));
			assertEquals(-1, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, t.x() + 1, t.y() + t.h()));
			assertEquals(-1, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, t.x() + 1, t.y() - 0.01));
			assertTrue(RtsCommandBar.pagedContains(PAGE, GLOBALS, TABS, guiWidth, guiHeight, t.x() + 1, t.y() + 1));
		}
		RtsCommandBar.Rect first = tabs.get(0);
		double gapX = first.x() + first.w() + RtsCommandBar.GAP / 2.0;
		double midY = first.y() + first.h() / 2.0;
		assertEquals(-1, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, gapX, midY));
		assertTrue(RtsCommandBar.pagedContains(PAGE, GLOBALS, TABS, guiWidth, guiHeight, gapX, midY));
		assertEquals(-1, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, first.x() - 0.01, midY));
		assertFalse(RtsCommandBar.pagedContains(PAGE, GLOBALS, TABS, guiWidth, guiHeight, first.x() - 0.01, midY));
		// The strip between the tabs and the buttons is neither.
		double between = first.y() + first.h() + RtsCommandBar.TAB_GAP / 2.0;
		assertEquals(-1, RtsCommandBar.tabHitTest(TABS, guiWidth, guiHeight, guiWidth / 2.0, between));
		assertEquals(RtsCommandBar.Hit.NONE, RtsCommandBar.pagedHitTest(PAGE, GLOBALS, guiWidth, guiHeight,
				guiWidth / 2.0, between));
		assertFalse(RtsCommandBar.pagedContains(PAGE, GLOBALS, TABS, guiWidth, guiHeight, guiWidth / 2.0,
				guiHeight / 2.0));
		// Without tabs, only the button row counts.
		assertFalse(RtsCommandBar.pagedContains(PAGE, GLOBALS, new int[] {41}, guiWidth, guiHeight, first.x() + 1,
				first.y() + 1));
	}

	@Test
	void pagedBarAndTabsFitOnTheSmallestScreenBelowTheHud() {
		int guiWidth = 320;
		int guiHeight = 240;
		List<RtsCommandBar.Rect> parts = new ArrayList<>(RtsCommandBar.pagedLayout(RtsCommandPage.MAX_SLOTS, GLOBALS,
				guiWidth, guiHeight));
		parts.addAll(RtsCommandBar.tabsLayout(TABS, guiWidth, guiHeight));
		for (RtsCommandBar.Rect r : parts) {
			assertTrue(r.x() >= 0 && r.x() + r.w() <= guiWidth, "inside horizontally: " + r);
			assertTrue(r.y() >= 0 && r.y() + r.h() <= guiHeight, "inside vertically: " + r);
			assertTrue(r.y() > HUD_BOTTOM, "below the HUD lines: " + r);
		}
	}
}
