package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * A field to till and plant, built from the two blocks picked by an RTS drag or given to a command.
 * It is an inclusive rectangle of columns plus a vertical window, {@link #WINDOW} blocks below the
 * lower corner and above the higher one, in which each column's top block is its field block. It holds
 * no Minecraft types so it can be unit-tested. It is always normalised so that {@code min <= max}.
 */
public record RtsField(int minX, int minZ, int maxX, int maxZ, int bottomY, int topY) {
	/** How far the window reaches below the lower picked corner and above the higher one. */
	public static final int WINDOW = 3;
	/** The most columns a field may have: 32×32. */
	public static final int MAX_COLUMNS = 1024;
	/** The largest height difference between the two picked corners. */
	public static final int MAX_RISE = 16;

	/** The field spanned by two picked blocks given in any order. */
	public static RtsField of(int ax, int ay, int az, int bx, int by, int bz) {
		return new RtsField(Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz),
				Math.min(ay, by) - WINDOW, Math.max(ay, by) + WINDOW);
	}

	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	/** The number of columns in the field. */
	public long columns() {
		return (long) sizeX() * sizeZ();
	}

	/** The height difference between the two picked corners. */
	public int rise() {
		return topY - bottomY - 2 * WINDOW;
	}

	/** Whether the field has more than {@link #MAX_COLUMNS} columns or more than {@link #MAX_RISE} rise. */
	public boolean exceeds() {
		return columns() > MAX_COLUMNS || rise() > MAX_RISE;
	}

	/**
	 * Every column as {@code {x, z}}, row by row along x and turning back at the end of each row, so a
	 * worker walks the field without crossing it.
	 */
	public List<int[]> columnOrder() {
		List<int[]> order = new ArrayList<>((int) Math.min(columns(), Integer.MAX_VALUE));
		boolean forward = true;
		for (int z = minZ; z <= maxZ; z++) {
			if (forward) {
				for (int x = minX; x <= maxX; x++) {
					order.add(new int[] {x, z});
				}
			} else {
				for (int x = maxX; x >= minX; x--) {
					order.add(new int[] {x, z});
				}
			}
			forward = !forward;
		}
		return order;
	}

	/**
	 * The highest y in {@code [bottomY, topY]} that is ground with no ground above it. It is empty when
	 * the column has no ground in the window, or when the ground goes on above the window (buried).
	 */
	public static OptionalInt surfaceY(int bottomY, int topY, IntPredicate isGround) {
		for (int y = topY; y >= bottomY; y--) {
			if (isGround.test(y) && !isGround.test(y + 1)) {
				return OptionalInt.of(y);
			}
		}
		return OptionalInt.empty();
	}

	/** The size and column count as shown next to the field, for example {@code "12×8 = 96"}: x × z. */
	public String label() {
		return sizeX() + "×" + sizeZ() + " = " + columns();
	}
}
