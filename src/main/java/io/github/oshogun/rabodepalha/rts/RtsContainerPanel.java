package io.github.oshogun.rabodepalha.rts;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout and hit-testing for the RTS withdraw view: a read-only snapshot of a container's contents. It holds a
 * header with a Close button, a grid of item cells, and a row with Minus, the count, Plus and Confirm. The box
 * sits at the left edge, its bottom level with the worker panel's lowest edge, so it stays clear of the worker
 * panel on the right and of the command bar below. Cells that don't fit are cut. All coordinates are GUI-scaled
 * pixels, with y growing downwards.
 */
public final class RtsContainerPanel {
	/** The widest the view gets, in GUI pixels. */
	public static final int MAX_WIDTH = 160;
	/** The width of the Close button in the header, in GUI pixels. */
	public static final int CLOSE_WIDTH = 32;
	/** The width the count label gets when there is room: enough for "3200 each". */
	public static final int COUNT_WIDTH = 56;
	/** The widest the Confirm button gets, in GUI pixels. */
	public static final int CONFIRM_WIDTH = 44;
	/** The side of the Minus and Plus buttons, in GUI pixels. */
	public static final int STEP_WIDTH = RtsPanel.ROW;

	private RtsContainerPanel() {
	}

	/**
	 * Where each part of the view goes. Parts that don't fit are {@link RtsPanel#EMPTY} or empty lists.
	 *
	 * @param bounds the whole view
	 * @param header the title row
	 * @param close the Close button, at the right end of the header
	 * @param items the visible item cells, in entry order
	 * @param hidden how many entries don't fit in the grid
	 * @param minus the button that lowers the count
	 * @param count the count label, between Minus and Plus
	 * @param plus the button that raises the count
	 * @param confirm the Confirm button, at the right end of the control row
	 */
	public record Layout(Rect bounds, Rect header, Rect close, List<Rect> items, int hidden, Rect minus, Rect count,
			Rect plus, Rect confirm) {
		public Layout {
			items = List.copyOf(items);
		}
	}

	/** What a point on the screen falls on. */
	public sealed interface Hit {
		/** Outside the view. */
		record None() implements Hit {
		}

		/** On the view, but on nothing clickable. */
		record Body() implements Hit {
		}

		/** On the item cell at {@code index}. */
		record Item(int index) implements Hit {
		}

		/** On the Minus button. */
		record Minus() implements Hit {
		}

		/** On the Plus button. */
		record Plus() implements Hit {
		}

		/** On the Confirm button. */
		record Confirm() implements Hit {
		}

		/** On the Close button. */
		record Close() implements Hit {
		}

		Hit NONE = new None();
		Hit BODY = new Body();
		Hit MINUS = new Minus();
		Hit PLUS = new Plus();
		Hit CONFIRM = new Confirm();
		Hit CLOSE = new Close();
	}

	/** The view's width on a GUI of the given width: up to the space left of the worker panel. */
	public static int width(int guiWidth) {
		return Math.max(0, Math.min(MAX_WIDTH, RtsPanel.leftTextMaxWidth(guiWidth)));
	}

	/** The layout for {@code entryCount} entries on a GUI of the given size. */
	public static Layout layout(int guiWidth, int guiHeight, int entryCount) {
		int pad = RtsPanel.PAD;
		int row = RtsPanel.ROW;
		int cell = RtsPanel.CELL;
		int gap = RtsPanel.GAP;
		int w = width(guiWidth);
		int x = RtsPanel.MARGIN;
		int innerX = x + pad;
		int innerW = Math.max(0, w - 2 * pad);
		int bottom = RtsPanel.maxBottom(guiHeight);
		int available = Math.max(0, bottom - RtsPanel.MARGIN);

		int entries = Math.max(0, entryCount);
		int columns = Math.max(1, innerW / cell);
		int itemRows = (entries + columns - 1) / columns;
		int fixed = 2 * pad + 2 * row + gap;
		int visibleRows = Math.min(itemRows, Math.max(0, (available - fixed - gap) / cell));
		int gridH = visibleRows == 0 ? 0 : visibleRows * cell + gap;
		int h = Math.min(available, fixed + gridH);
		int top = bottom - h;

		int y = top + pad;
		Rect header = new Rect(innerX, y, innerW, row);
		int closeW = Math.min(CLOSE_WIDTH, innerW);
		Rect close = new Rect(innerX + innerW - closeW, y, closeW, row);
		y += row + gap;

		List<Rect> items = new ArrayList<>();
		int visible = Math.min(entries, visibleRows * columns);
		for (int i = 0; i < visible; i++) {
			items.add(new Rect(innerX + (i % columns) * cell, y + (i / columns) * cell, cell, cell));
		}
		y += gridH;

		int stepW = Math.min(STEP_WIDTH, innerW / 4);
		int confirmW = Math.min(CONFIRM_WIDTH, Math.max(0, innerW - 2 * stepW) / 2);
		int countW = Math.max(0, innerW - 2 * stepW - confirmW);
		Rect minus = new Rect(innerX, y, stepW, row);
		Rect count = new Rect(innerX + stepW, y, countW, row);
		Rect plus = new Rect(innerX + stepW + countW, y, stepW, row);
		Rect confirm = new Rect(innerX + innerW - confirmW, y, confirmW, row);
		// Spare width goes between Plus and Confirm, keeping the count next to its buttons.
		if (countW > COUNT_WIDTH) {
			count = new Rect(count.x(), y, COUNT_WIDTH, row);
			plus = new Rect(count.x() + COUNT_WIDTH, y, stepW, row);
		}

		return new Layout(new Rect(x, top, w, h), header, close, items, entries - visible, minus, count, plus,
				confirm);
	}

	/** What the point falls on in the given layout. */
	public static Hit hitTest(Layout layout, double guiX, double guiY) {
		if (!layout.bounds().contains(guiX, guiY)) {
			return Hit.NONE;
		}
		if (layout.close().contains(guiX, guiY)) {
			return Hit.CLOSE;
		}
		for (int i = 0; i < layout.items().size(); i++) {
			if (layout.items().get(i).contains(guiX, guiY)) {
				return new Hit.Item(i);
			}
		}
		if (layout.minus().contains(guiX, guiY)) {
			return Hit.MINUS;
		}
		if (layout.plus().contains(guiX, guiY)) {
			return Hit.PLUS;
		}
		if (layout.confirm().contains(guiX, guiY)) {
			return Hit.CONFIRM;
		}
		return Hit.BODY;
	}
}
