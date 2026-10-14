package io.github.oshogun.rabodepalha.rts;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar.Rect;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout and hit-testing for the RTS side panel: a box anchored at the top right of the screen showing the
 * watched workers. With one worker selected it holds a header with a Clear button, the current task, the
 * queued tasks with a Remove button each, and the inventory grid; with several, one row per worker and a grid
 * of their combined items. Inventory and item cells are clickable, to pick an item. The box ends above the
 * command bar, and rows that don't fit collapse into a "+N more" row. All coordinates are GUI-scaled pixels,
 * with y growing downwards.
 */
public final class RtsPanel {
	/** The space between the panel and the top and right edges of the screen, in GUI pixels. */
	public static final int MARGIN = 4;
	/** The widest the panel gets, in GUI pixels; narrow screens get a third of their width. */
	public static final int MAX_WIDTH = 160;
	/** The space between the panel's edge and its contents, in GUI pixels. */
	public static final int PAD = 2;
	/** The space between the panel and the command bar or the left HUD lines, and before the inventory grid. */
	public static final int GAP = 4;
	/** The height of a text row, in GUI pixels. */
	public static final int ROW = 10;
	/** The side of an inventory cell, in GUI pixels: an item plus a 1px border on each side. */
	public static final int CELL = 18;
	/** The width of the Clear button in the header, in GUI pixels. */
	public static final int CLEAR_WIDTH = 32;
	/** The side of a queued task's Remove button, in GUI pixels. */
	public static final int REMOVE_WIDTH = ROW;
	/** An area that contains no point, for parts a layout doesn't have. */
	public static final Rect EMPTY = new Rect(0, 0, 0, 0);

	private RtsPanel() {
	}

	public enum Mode {
		/** One worker: header, current task, queued tasks and inventory. */
		SINGLE,
		/** Several workers: one row each. */
		MULTI
	}

	/**
	 * Where each part of the panel goes. Parts the mode doesn't have, or that don't fit, are {@link #EMPTY} or
	 * empty lists.
	 *
	 * @param bounds the whole panel
	 * @param header the worker's name row (SINGLE)
	 * @param clear the Clear button, at the right end of the header (SINGLE)
	 * @param current the current task row (SINGLE)
	 * @param queuedLabel the row above the queued tasks, present when there are any (SINGLE)
	 * @param queued the visible queued task rows, in queue order (SINGLE)
	 * @param remove the Remove button of each visible queued task, at the right end of its row (SINGLE)
	 * @param rows the visible worker rows, in selection order (MULTI)
	 * @param more the "+N more" row for queued tasks or workers that don't fit
	 * @param hidden how many queued tasks or workers the "+N more" row stands for
	 * @param slots the visible inventory cells, in slot order (SINGLE)
	 * @param items the visible cells of the workers' combined items, in entry order (MULTI)
	 */
	public record Layout(Mode mode, Rect bounds, Rect header, Rect clear, Rect current, Rect queuedLabel,
			List<Rect> queued, List<Rect> remove, List<Rect> rows, Rect more, int hidden, List<Rect> slots,
			List<Rect> items) {
		public Layout {
			queued = List.copyOf(queued);
			remove = List.copyOf(remove);
			rows = List.copyOf(rows);
			slots = List.copyOf(slots);
			items = List.copyOf(items);
		}
	}

	/** What a point on the screen falls on. */
	public sealed interface Hit {
		/** Outside the panel. */
		record None() implements Hit {
		}

		/** On the panel, but on nothing clickable. */
		record Body() implements Hit {
		}

		/** On the Clear button. */
		record Clear() implements Hit {
		}

		/** On the Remove button of the queued task at {@code index}. */
		record Remove(int index) implements Hit {
		}

		/** On the row of the worker at {@code index}. */
		record Row(int index) implements Hit {
		}

		/** On the item cell at {@code index}: an inventory slot (SINGLE) or a combined entry (MULTI). */
		record Item(int index) implements Hit {
		}

		Hit NONE = new None();
		Hit BODY = new Body();
		Hit CLEAR = new Clear();
	}

	/** The panel's width on a GUI of the given width. */
	public static int width(int guiWidth) {
		return Math.max(0, Math.min(MAX_WIDTH, guiWidth / 3));
	}

	/** The panel's left edge on a GUI of the given width. */
	public static int left(int guiWidth) {
		return guiWidth - MARGIN - width(guiWidth);
	}

	/** The lowest the panel's bottom edge may go: {@link #GAP} above the command bar. */
	public static int maxBottom(int guiHeight) {
		return guiHeight - RtsCommandBar.BOTTOM_MARGIN - RtsCommandBar.BUTTON - GAP;
	}

	/** The widest a HUD line drawn at the left margin may be without reaching the panel. */
	public static int leftTextMaxWidth(int guiWidth) {
		return Math.max(0, left(guiWidth) - GAP - MARGIN);
	}

	/** How many inventory cells fit side by side. */
	public static int columns(int guiWidth) {
		return Math.max(1, (width(guiWidth) - 2 * PAD) / CELL);
	}

	/**
	 * The layout for one worker with {@code queuedCount} queued tasks and {@code slotCount} inventory slots.
	 * Queued tasks give way to the inventory, down to a single inventory row; the rest of the inventory is cut.
	 */
	public static Layout single(int queuedCount, int slotCount, int guiWidth, int guiHeight) {
		int w = width(guiWidth);
		int x = left(guiWidth);
		int innerX = x + PAD;
		int innerW = w - 2 * PAD;
		int top = MARGIN;
		int limit = maxBottom(guiHeight) - PAD;
		int y = top + PAD;

		Rect header = EMPTY;
		Rect clear = EMPTY;
		if (y + ROW <= limit) {
			header = new Rect(innerX, y, innerW, ROW);
			int clearW = Math.min(CLEAR_WIDTH, innerW);
			clear = new Rect(innerX + innerW - clearW, y, clearW, ROW);
			y += ROW;
		}
		Rect current = EMPTY;
		if (y + ROW <= limit) {
			current = new Rect(innerX, y, innerW, ROW);
			y += ROW;
		}

		int columns = columns(guiWidth);
		int slots = Math.max(0, slotCount);
		int inventoryRows = (slots + columns - 1) / columns;
		int reserve = inventoryRows == 0 ? 0 : GAP + inventoryRows * CELL;
		Rect queuedLabel = EMPTY;
		List<Rect> queued = new ArrayList<>();
		List<Rect> remove = new ArrayList<>();
		Rect more = EMPTY;
		int hidden = 0;
		if (queuedCount > 0) {
			// The label plus at least one row; failing that, keep only one inventory row.
			if (inventoryRows > 1 && limit - y - reserve < 2 * ROW) {
				reserve = GAP + CELL;
			}
			int fit = Math.max(0, (limit - y - reserve) / ROW);
			if (fit >= 2) {
				queuedLabel = new Rect(innerX, y, innerW, ROW);
				y += ROW;
				fit--;
				int shown = queuedCount <= fit ? queuedCount : fit - 1;
				for (int i = 0; i < shown; i++) {
					queued.add(new Rect(innerX, y, innerW, ROW));
					remove.add(new Rect(innerX + innerW - REMOVE_WIDTH, y, REMOVE_WIDTH, ROW));
					y += ROW;
				}
				hidden = queuedCount - shown;
				if (hidden > 0) {
					more = new Rect(innerX, y, innerW, ROW);
					y += ROW;
				}
			} else {
				hidden = queuedCount;
			}
		}

		List<Rect> slotRects = new ArrayList<>();
		if (inventoryRows > 0) {
			int visibleRows = Math.min(inventoryRows, Math.max(0, (limit - y - GAP) / CELL));
			if (visibleRows > 0) {
				y += GAP;
				int visible = Math.min(slots, visibleRows * columns);
				for (int i = 0; i < visible; i++) {
					slotRects.add(new Rect(innerX + (i % columns) * CELL, y + (i / columns) * CELL, CELL, CELL));
				}
				y += visibleRows * CELL;
			}
		}

		Rect bounds = new Rect(x, top, w, Math.max(0, y + PAD - top));
		return new Layout(Mode.SINGLE, bounds, header, clear, current, queuedLabel, queued, remove, List.of(), more,
				hidden, slotRects, List.of());
	}

	/** The layout for {@code workerCount} workers, one row each, without a combined item grid. */
	public static Layout multi(int workerCount, int guiWidth, int guiHeight) {
		return multi(workerCount, 0, guiWidth, guiHeight);
	}

	/**
	 * The layout for {@code workerCount} workers, one row each, and a grid of {@code itemCount} combined items
	 * below them. Worker rows give way to the item grid, down to a single row; the rest of the grid is cut.
	 */
	public static Layout multi(int workerCount, int itemCount, int guiWidth, int guiHeight) {
		int w = width(guiWidth);
		int x = left(guiWidth);
		int innerX = x + PAD;
		int innerW = w - 2 * PAD;
		int top = MARGIN;
		int limit = maxBottom(guiHeight) - PAD;
		int y = top + PAD;

		int columns = columns(guiWidth);
		int entries = Math.max(0, itemCount);
		int itemRows = (entries + columns - 1) / columns;
		int count = Math.max(0, workerCount);
		// Keep room for one worker row (or the "+N more" row); the grid takes the rest.
		int keep = count > 0 ? ROW : 0;
		int visibleItemRows = Math.min(itemRows, Math.max(0, (limit - y - keep - GAP) / CELL));
		int reserve = visibleItemRows == 0 ? 0 : GAP + visibleItemRows * CELL;

		int fit = Math.max(0, (limit - y - reserve) / ROW);
		int shown = count <= fit ? count : Math.max(0, fit - 1);
		List<Rect> rows = new ArrayList<>();
		for (int i = 0; i < shown; i++) {
			rows.add(new Rect(innerX, y, innerW, ROW));
			y += ROW;
		}
		int hidden = count - shown;
		Rect more = EMPTY;
		if (hidden > 0 && y + ROW <= limit - reserve) {
			more = new Rect(innerX, y, innerW, ROW);
			y += ROW;
		}

		List<Rect> itemRects = new ArrayList<>();
		if (visibleItemRows > 0) {
			y += GAP;
			int visible = Math.min(entries, visibleItemRows * columns);
			for (int i = 0; i < visible; i++) {
				itemRects.add(new Rect(innerX + (i % columns) * CELL, y + (i / columns) * CELL, CELL, CELL));
			}
			y += visibleItemRows * CELL;
		}

		Rect bounds = new Rect(x, top, w, Math.max(0, y + PAD - top));
		return new Layout(Mode.MULTI, bounds, EMPTY, EMPTY, EMPTY, EMPTY, List.of(), List.of(), rows, more, hidden,
				List.of(), itemRects);
	}

	/** What the point falls on in the given layout. */
	public static Hit hitTest(Layout layout, double guiX, double guiY) {
		if (!layout.bounds().contains(guiX, guiY)) {
			return Hit.NONE;
		}
		if (layout.mode() == Mode.SINGLE) {
			if (layout.clear().contains(guiX, guiY)) {
				return Hit.CLEAR;
			}
			for (int i = 0; i < layout.remove().size(); i++) {
				if (layout.remove().get(i).contains(guiX, guiY)) {
					return new Hit.Remove(i);
				}
			}
			for (int i = 0; i < layout.slots().size(); i++) {
				if (layout.slots().get(i).contains(guiX, guiY)) {
					return new Hit.Item(i);
				}
			}
		} else {
			for (int i = 0; i < layout.rows().size(); i++) {
				if (layout.rows().get(i).contains(guiX, guiY)) {
					return new Hit.Row(i);
				}
			}
			for (int i = 0; i < layout.items().size(); i++) {
				if (layout.items().get(i).contains(guiX, guiY)) {
					return new Hit.Item(i);
				}
			}
		}
		return Hit.BODY;
	}
}
