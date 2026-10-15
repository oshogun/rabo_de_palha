package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout and hit-testing for the RTS command bar: a row of square buttons centred at the bottom of
 * the screen. The vanilla hotbar is hidden in RTS mode, so the bar takes its place and stays clear
 * of the HUD lines drawn at the top left. The paged bar shows the current page's buttons, then the
 * global ones, with a row of page tabs just above. All coordinates are GUI-scaled pixels, with y
 * growing downwards.
 */
public final class RtsCommandBar {
	/** The side of a button, in GUI pixels. */
	public static final int BUTTON = 20;
	/** The space between two buttons, in GUI pixels. */
	public static final int GAP = 2;
	/** The space between the bottom of the bar and the bottom of the screen, in GUI pixels. */
	public static final int BOTTOM_MARGIN = 4;
	/** The space between the current page's buttons and the global ones, in GUI pixels. */
	public static final int GROUP_GAP = 8;
	/** The height of a page tab, in GUI pixels: a line of text plus a 1px margin above and below. */
	public static final int TAB_HEIGHT = 11;
	/** The space between the tab row and the buttons below it, in GUI pixels. */
	public static final int TAB_GAP = 2;
	/** The space between a tab's name and each of its sides, in GUI pixels. */
	public static final int TAB_PAD = 4;

	private RtsCommandBar() {
	}

	/** A button's area. The left and top edges are inside it; the right and bottom edges are not. */
	public record Rect(int x, int y, int w, int h) {
		public boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	/** The width of a bar holding {@code count} buttons. */
	public static int width(int count) {
		return count <= 0 ? 0 : count * BUTTON + (count - 1) * GAP;
	}

	/** The areas of {@code count} buttons, from left to right, on a GUI of the given size. */
	public static List<Rect> layout(int count, int guiWidth, int guiHeight) {
		List<Rect> rects = new ArrayList<>(Math.max(count, 0));
		int left = (guiWidth - width(count)) / 2;
		int top = guiHeight - BOTTOM_MARGIN - BUTTON;
		for (int i = 0; i < count; i++) {
			rects.add(new Rect(left + i * (BUTTON + GAP), top, BUTTON, BUTTON));
		}
		return rects;
	}

	/** The whole bar's area, gaps between buttons included; empty when there are no buttons. */
	public static Rect bounds(int count, int guiWidth, int guiHeight) {
		if (count <= 0) {
			return new Rect(0, 0, 0, 0);
		}
		return new Rect((guiWidth - width(count)) / 2, guiHeight - BOTTOM_MARGIN - BUTTON, width(count), BUTTON);
	}

	/** Whether the point is on the bar, on a button or in a gap between two. */
	public static boolean contains(int count, int guiWidth, int guiHeight, double guiX, double guiY) {
		return bounds(count, guiWidth, guiHeight).contains(guiX, guiY);
	}

	/** The index of the button under the point, or -1 when the point is outside the bar or in a gap. */
	public static int hitTest(int count, int guiWidth, int guiHeight, double guiX, double guiY) {
		List<Rect> rects = layout(count, guiWidth, guiHeight);
		for (int i = 0; i < rects.size(); i++) {
			if (rects.get(i).contains(guiX, guiY)) {
				return i;
			}
		}
		return -1;
	}

	/** What a point on the paged bar falls on: a page button, a global button, or neither. */
	public record Hit(Kind kind, int index) {
		public static final Hit NONE = new Hit(Kind.NONE, -1);

		public enum Kind {
			PAGE,
			GLOBAL,
			NONE
		}
	}

	/** The width of a paged bar: {@code pageCount} page buttons, then {@code globalCount} global buttons. */
	public static int pagedWidth(int pageCount, int globalCount) {
		int gap = pageCount > 0 && globalCount > 0 ? GROUP_GAP : 0;
		return width(pageCount) + gap + width(globalCount);
	}

	/**
	 * The buttons of a paged bar, from left to right: the current page's {@code pageCount} buttons, then, after
	 * {@link #GROUP_GAP}, the {@code globalCount} buttons shown on every page. The row is centred at the bottom.
	 */
	public static List<Rect> pagedLayout(int pageCount, int globalCount, int guiWidth, int guiHeight) {
		int pages = Math.max(pageCount, 0);
		int globals = Math.max(globalCount, 0);
		List<Rect> rects = new ArrayList<>(pages + globals);
		int left = (guiWidth - pagedWidth(pages, globals)) / 2;
		int top = guiHeight - BOTTOM_MARGIN - BUTTON;
		for (int i = 0; i < pages; i++) {
			rects.add(new Rect(left + i * (BUTTON + GAP), top, BUTTON, BUTTON));
		}
		int globalLeft = left + width(pages) + (pages > 0 && globals > 0 ? GROUP_GAP : 0);
		for (int i = 0; i < globals; i++) {
			rects.add(new Rect(globalLeft + i * (BUTTON + GAP), top, BUTTON, BUTTON));
		}
		return rects;
	}

	/** The paged bar's button row, gaps included; empty when there are no buttons. */
	public static Rect pagedBounds(int pageCount, int globalCount, int guiWidth, int guiHeight) {
		int w = pagedWidth(Math.max(pageCount, 0), Math.max(globalCount, 0));
		if (w <= 0) {
			return new Rect(0, 0, 0, 0);
		}
		return new Rect((guiWidth - w) / 2, guiHeight - BOTTOM_MARGIN - BUTTON, w, BUTTON);
	}

	/** The button under the point; gaps and everything off the row are {@link Hit#NONE}. */
	public static Hit pagedHitTest(int pageCount, int globalCount, int guiWidth, int guiHeight, double guiX,
			double guiY) {
		List<Rect> rects = pagedLayout(pageCount, globalCount, guiWidth, guiHeight);
		int pages = Math.max(pageCount, 0);
		for (int i = 0; i < rects.size(); i++) {
			if (rects.get(i).contains(guiX, guiY)) {
				return i < pages ? new Hit(Hit.Kind.PAGE, i) : new Hit(Hit.Kind.GLOBAL, i - pages);
			}
		}
		return Hit.NONE;
	}

	/**
	 * The page tabs, from left to right, in a row centred just above the bar; {@code tabWidths} are measured by
	 * the caller with the font. A single page needs no tabs, so fewer than two widths give no tabs.
	 */
	public static List<Rect> tabsLayout(int[] tabWidths, int guiWidth, int guiHeight) {
		if (tabWidths.length < 2) {
			return List.of();
		}
		List<Rect> rects = new ArrayList<>(tabWidths.length);
		int x = (guiWidth - tabsWidth(tabWidths)) / 2;
		int y = guiHeight - BOTTOM_MARGIN - BUTTON - TAB_GAP - TAB_HEIGHT;
		for (int w : tabWidths) {
			rects.add(new Rect(x, y, w, TAB_HEIGHT));
			x += w + GAP;
		}
		return rects;
	}

	/** The index of the tab under the point, or -1 when it is off the tabs or in a gap between two. */
	public static int tabHitTest(int[] tabWidths, int guiWidth, int guiHeight, double guiX, double guiY) {
		List<Rect> rects = tabsLayout(tabWidths, guiWidth, guiHeight);
		for (int i = 0; i < rects.size(); i++) {
			if (rects.get(i).contains(guiX, guiY)) {
				return i;
			}
		}
		return -1;
	}

	/** Whether the point is on the paged bar's button row or its tab row, gaps included. */
	public static boolean pagedContains(int pageCount, int globalCount, int[] tabWidths, int guiWidth,
			int guiHeight, double guiX, double guiY) {
		if (pagedBounds(pageCount, globalCount, guiWidth, guiHeight).contains(guiX, guiY)) {
			return true;
		}
		List<Rect> tabs = tabsLayout(tabWidths, guiWidth, guiHeight);
		if (tabs.isEmpty()) {
			return false;
		}
		Rect first = tabs.get(0);
		return new Rect(first.x(), first.y(), tabsWidth(tabWidths), TAB_HEIGHT).contains(guiX, guiY);
	}

	/** The top of the whole bar block with {@code tabCount} tabs; the tab row counts only from two tabs on. */
	public static int top(int tabCount, int guiHeight) {
		int top = guiHeight - BOTTOM_MARGIN - BUTTON;
		return tabCount >= 2 ? top - TAB_GAP - TAB_HEIGHT : top;
	}

	/** The highest the bar block reaches, tab row included: where panels above the bar must stop. */
	public static int maxTop(int guiHeight) {
		return top(2, guiHeight);
	}

	private static int tabsWidth(int[] tabWidths) {
		int w = 0;
		for (int tab : tabWidths) {
			w += tab;
		}
		return tabWidths.length == 0 ? 0 : w + (tabWidths.length - 1) * GAP;
	}

	/** Converts a window pixel coordinate to GUI pixels. */
	public static double toGui(double windowPixel, double guiScale) {
		return windowPixel / guiScale;
	}
}
