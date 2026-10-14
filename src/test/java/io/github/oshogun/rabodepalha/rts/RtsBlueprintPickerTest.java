package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import org.junit.jupiter.api.Test;

class RtsBlueprintPickerTest {
	private static final int BAR_COUNT = 8;
	private static final int[][] WINDOWS = {{1920, 1080}, {1280, 720}, {854, 480}, {640, 480}};

	private static boolean overlaps(Rect a, Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	@Test
	void pickerStaysInsideAndClearOfThePanelAndTheBarAtEveryScale() {
		for (int[] window : WINDOWS) {
			for (int scale = 1; scale <= 4; scale++) {
				int guiWidth = window[0] / scale;
				int guiHeight = window[1] / scale;
				Rect bar = RtsCommandBar.bounds(BAR_COUNT, guiWidth, guiHeight);
				int panelLeft = RtsPanel.left(guiWidth);
				for (int rows : new int[] {0, 1, 5, 100}) {
					RtsBlueprintPicker.Layout l = RtsBlueprintPicker.layout(guiWidth, guiHeight, rows, 0);
					Rect b = l.bounds();
					String where = window[0] + "x" + window[1] + " @" + scale + " rows " + rows + " " + b;
					assertTrue(b.x() >= 0 && b.y() >= 0 && b.x() + b.w() <= guiWidth, where);
					assertFalse(overlaps(b, bar), where);
					assertTrue(b.x() + b.w() <= panelLeft, where);
					assertTrue(l.rows().size() <= RtsBlueprintPicker.MAX_ROWS, where);
					for (Rect row : l.rows()) {
						assertTrue(row.y() >= b.y() && row.y() + row.h() <= l.toggle().y(), where);
					}
				}
			}
		}
	}

	@Test
	void scrollIsClampedAndHitsCountFromTheTopOfTheList() {
		RtsBlueprintPicker.Layout top = RtsBlueprintPicker.layout(1920 / 2, 1080 / 2, 30, -5);
		assertEquals(0, top.first());
		assertEquals(30 - RtsBlueprintPicker.MAX_ROWS, top.maxScroll());

		RtsBlueprintPicker.Layout end = RtsBlueprintPicker.layout(1920 / 2, 1080 / 2, 30, 999);
		assertEquals(end.maxScroll(), end.first());
		Rect firstRow = end.rows().get(0);
		RtsBlueprintPicker.Hit hit = RtsBlueprintPicker.hitTest(end, firstRow.x() + 1, firstRow.y() + 1);
		assertEquals(new RtsBlueprintPicker.Hit.Row(end.first()), hit);
	}

	@Test
	void hitTestFindsTheToggleTheBodyAndNothingOutside() {
		RtsBlueprintPicker.Layout l = RtsBlueprintPicker.layout(480, 270, 3, 0);
		Rect toggle = l.toggle();
		assertInstanceOf(RtsBlueprintPicker.Hit.Toggle.class,
				RtsBlueprintPicker.hitTest(l, toggle.x() + 1, toggle.y() + 1));
		assertInstanceOf(RtsBlueprintPicker.Hit.Body.class,
				RtsBlueprintPicker.hitTest(l, l.bounds().x(), l.bounds().y()));
		assertInstanceOf(RtsBlueprintPicker.Hit.None.class,
				RtsBlueprintPicker.hitTest(l, l.bounds().x() - 1, l.bounds().y()));
	}
}
