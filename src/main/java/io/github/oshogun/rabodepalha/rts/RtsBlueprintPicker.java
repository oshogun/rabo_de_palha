package io.github.oshogun.rabodepalha.rts;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout and hit-testing for the RTS blueprint picker: a list of rows centred above the command bar, with the
 * "Show vanilla structures" toggle pinned below them. The rows above the toggle scroll when they don't fit. The
 * picker keeps to the middle third of the screen, clear of the worker panel on the right. All coordinates are
 * GUI-scaled pixels, with y growing downwards.
 */
public final class RtsBlueprintPicker {
	/** The widest the picker gets, in GUI pixels. */
	public static final int MAX_WIDTH = 160;
	/** The most list rows shown at once. */
	public static final int MAX_ROWS = 12;

	private RtsBlueprintPicker() {
	}

	/**
	 * Where each part of the picker goes.
	 *
	 * @param bounds the whole picker
	 * @param rows the visible list rows, for list rows {@code first} onwards
	 * @param first the index of the first visible list row
	 * @param maxScroll the largest useful scroll, in rows
	 * @param toggle the vanilla toggle row, below the list
	 */
	public record Layout(Rect bounds, List<Rect> rows, int first, int maxScroll, Rect toggle) {
		public Layout {
			rows = List.copyOf(rows);
		}
	}

	/** What a point on the screen falls on. */
	public sealed interface Hit {
		/** Outside the picker. */
		record None() implements Hit {
		}

		/** On the picker, but on nothing clickable. */
		record Body() implements Hit {
		}

		/** On list row {@code index}, counted from the top of the whole list, not the visible part. */
		record Row(int index) implements Hit {
		}

		/** On the vanilla toggle. */
		record Toggle() implements Hit {
		}

		Hit NONE = new None();
		Hit BODY = new Body();
		Hit TOGGLE = new Toggle();
	}

	/** The picker's width on a GUI of the given width: up to the middle third of the screen. */
	public static int width(int guiWidth) {
		return Math.max(0, Math.min(MAX_WIDTH, guiWidth / 3 - 2 * RtsPanel.GAP));
	}

	/** The layout for {@code rowCount} list rows scrolled by {@code scroll} rows, on a GUI of the given size. */
	public static Layout layout(int guiWidth, int guiHeight, int rowCount, int scroll) {
		int pad = RtsPanel.PAD;
		int row = RtsPanel.ROW;
		int w = width(guiWidth);
		int x = (guiWidth - w) / 2;
		int innerX = x + pad;
		int innerW = Math.max(0, w - 2 * pad);
		int bottom = RtsPanel.maxBottom(guiHeight);
		int available = Math.max(0, bottom - RtsPanel.MARGIN);

		int rows = Math.max(0, rowCount);
		int fits = Math.max(0, (available - 2 * pad - row) / row);
		int visible = Math.min(rows, Math.min(MAX_ROWS, fits));
		int maxScroll = rows - visible;
		int first = Math.max(0, Math.min(scroll, maxScroll));
		int h = Math.min(available, 2 * pad + (visible + 1) * row);
		int top = bottom - h;

		List<Rect> rects = new ArrayList<>(visible);
		for (int i = 0; i < visible; i++) {
			rects.add(new Rect(innerX, top + pad + i * row, innerW, row));
		}
		Rect toggle = h >= 2 * pad + row ? new Rect(innerX, top + pad + visible * row, innerW, row) : RtsPanel.EMPTY;
		return new Layout(new Rect(x, top, w, h), rects, first, maxScroll, toggle);
	}

	/** What the point falls on in the given layout. */
	public static Hit hitTest(Layout layout, double guiX, double guiY) {
		if (!layout.bounds().contains(guiX, guiY)) {
			return Hit.NONE;
		}
		if (layout.toggle().contains(guiX, guiY)) {
			return Hit.TOGGLE;
		}
		for (int i = 0; i < layout.rows().size(); i++) {
			if (layout.rows().get(i).contains(guiX, guiY)) {
				return new Hit.Row(layout.first() + i);
			}
		}
		return Hit.BODY;
	}
}
