package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout and hit-testing for the RTS command bar: a row of square buttons centred at the bottom of
 * the screen. The vanilla hotbar is hidden in RTS mode, so the bar takes its place and stays clear
 * of the HUD lines drawn at the top left. All coordinates are GUI-scaled pixels, with y growing
 * downwards.
 */
public final class RtsCommandBar {
	/** The side of a button, in GUI pixels. */
	public static final int BUTTON = 20;
	/** The space between two buttons, in GUI pixels. */
	public static final int GAP = 2;
	/** The space between the bottom of the bar and the bottom of the screen, in GUI pixels. */
	public static final int BOTTOM_MARGIN = 4;

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

	/** Converts a window pixel coordinate to GUI pixels. */
	public static double toGui(double windowPixel, double guiScale) {
		return windowPixel / guiScale;
	}
}
