package io.github.oshogun.rabodepalha.rts;

/**
 * An axis-aligned screen rectangle in pixels, with y growing downwards, used for box selection.
 * It is always normalised so that {@code min <= max} on both axes.
 */
public record RtsScreenRect(double minX, double minY, double maxX, double maxY) {
	/** How far, in pixels on either axis, the cursor must move before a press becomes a box drag. */
	public static final double DRAG_THRESHOLD_PX = 4.0;

	/** The rectangle spanned by two corners given in any order. */
	public static RtsScreenRect fromCorners(double x0, double y0, double x1, double y1) {
		return new RtsScreenRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
	}

	/** Whether moving from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) is a drag rather than a click. */
	public static boolean isDrag(double x0, double y0, double x1, double y1) {
		return Math.abs(x1 - x0) > DRAG_THRESHOLD_PX || Math.abs(y1 - y0) > DRAG_THRESHOLD_PX;
	}

	/** Whether the point lies inside the rectangle; the edges count as inside. */
	public boolean contains(double x, double y) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY;
	}

	public double width() {
		return maxX - minX;
	}

	public double height() {
		return maxY - minY;
	}
}
