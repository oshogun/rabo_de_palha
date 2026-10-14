package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RtsCommandBarTest {
	/** The number of buttons the bar ships with: Move, Mine, Place, Stop and Spawn worker. */
	private static final int COUNT = 5;
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
}
