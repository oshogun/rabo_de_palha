package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Pure enclosed-space detection for the RTS roofs-off and walls-down cuts: finds the room around a cell,
 * Sims style, from block solidity alone. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>A room is the air cells of one layer, 4-connected, that have a ceiling within {@link #CEILING_REACH}
 * blocks above. Air cells without a ceiling are openings and stop the fill; solid cells are walls. A fill
 * that grows past {@link #MAX_CELLS} cells or a {@link #MAX_EXTENT}-block extent is a large cave or a
 * forest, and one whose edges are more than {@link #MAX_OPENING_RATIO} openings is an overhang; neither
 * is a room. Large spaces are left to the global view level.
 */
public final class CutawayRooms {
	/** How far above a cell its ceiling may be, in blocks. */
	public static final int CEILING_REACH = 6;
	/** The most cells a room may have. */
	public static final int MAX_CELLS = 512;
	/** The widest a room may be along x or z, in blocks. */
	public static final int MAX_EXTENT = 32;
	/** The largest share of a room's edges that may open to the sky. */
	public static final double MAX_OPENING_RATIO = 0.25;
	/** How many solid blocks the start may probe down through, from a hovered roof to the room under it. */
	public static final int ROOF_PROBE = 3;

	private CutawayRooms() {
	}

	/** Which blocks stop the fill and which count as a ceiling. */
	public interface Solidity {
		/** Whether the block is a wall or floor: the fill does not pass it. */
		boolean solid(int x, int y, int z);

		/** Whether the block covers the cells below it; by default the solid ones. */
		default boolean ceiling(int x, int y, int z) {
			return solid(x, y, z);
		}
	}

	/** A block cell. */
	public record Cell(int x, int y, int z) {
	}

	/**
	 * A detected room: the interior box of its cells on x and z, the layer it was filled at (the feet layer,
	 * one above the floor blocks) and the lowest ceiling block above it.
	 */
	public record Room(int minX, int minZ, int maxX, int maxZ, int floorY, int ceilingY) {
	}

	/**
	 * The feet cell a room search starts from when the cursor hits block {@code hitX, hitY, hitZ} on the face
	 * with normal {@code faceDx, faceDy, faceDz}: the air cell in front of that face when it is covered,
	 * otherwise, when the hit block is a roof, the first air cell under it through at most
	 * {@link #ROOF_PROBE} solid blocks. The cell then drops onto the floor below it. Null when neither works.
	 */
	public static @Nullable Cell start(Solidity world, int hitX, int hitY, int hitZ, int faceDx, int faceDy,
			int faceDz) {
		int x = hitX + faceDx;
		int y = hitY + faceDy;
		int z = hitZ + faceDz;
		if (!world.solid(x, y, z) && hasCeiling(world, x, y, z)) {
			return toFloor(world, x, y, z);
		}
		if (!world.solid(hitX, hitY, hitZ)) {
			return null;
		}
		for (int depth = 1; depth <= ROOF_PROBE; depth++) {
			if (!world.solid(hitX, hitY - depth, hitZ)) {
				return toFloor(world, hitX, hitY - depth, hitZ);
			}
		}
		return null;
	}

	/** Drops the air cell {@code x, y, z} to the lowest air cell above solid ground, at most a ceiling reach. */
	private static Cell toFloor(Solidity world, int x, int y, int z) {
		int floor = y;
		while (floor > y - CEILING_REACH && !world.solid(x, floor - 1, z)) {
			floor--;
		}
		return new Cell(x, floor, z);
	}

	/** Whether a ceiling block lies within {@link #CEILING_REACH} above the cell. */
	public static boolean hasCeiling(Solidity world, int x, int y, int z) {
		return ceilingAbove(world, x, y, z) != Integer.MAX_VALUE;
	}

	private static int ceilingAbove(Solidity world, int x, int y, int z) {
		for (int dy = 1; dy <= CEILING_REACH; dy++) {
			if (world.ceiling(x, y + dy, z)) {
				return y + dy;
			}
		}
		return Integer.MAX_VALUE;
	}

	/** The room around the air cell {@code x, y, z}, or null when it is not in an enclosed space. */
	public static @Nullable Room detect(Solidity world, int x, int y, int z) {
		if (world.solid(x, y, z) || !hasCeiling(world, x, y, z)) {
			return null;
		}
		// The fill stays within MAX_EXTENT of the start, so a grid of twice that around it holds every cell.
		int size = 2 * MAX_EXTENT + 1;
		boolean[] seen = new boolean[size * size];
		int[] queue = new int[MAX_CELLS + 1];
		int head = 0;
		int tail = 0;
		seen[index(MAX_EXTENT, MAX_EXTENT, size)] = true;
		queue[tail++] = index(MAX_EXTENT, MAX_EXTENT, size);
		int minX = x;
		int maxX = x;
		int minZ = z;
		int maxZ = z;
		// The box and lowest ceiling of the cells that aren't doorways, so an open door neither widens the room
		// into its wall nor lowers the cut to the lintel.
		int roomMinX = Integer.MAX_VALUE;
		int roomMaxX = Integer.MIN_VALUE;
		int roomMinZ = Integer.MAX_VALUE;
		int roomMaxZ = Integer.MIN_VALUE;
		int ceiling = Integer.MAX_VALUE;
		int roomCeiling = Integer.MAX_VALUE;
		int walls = 0;
		int openings = 0;
		while (head < tail) {
			int cell = queue[head++];
			int cx = x + cell % size - MAX_EXTENT;
			int cz = z + cell / size - MAX_EXTENT;
			int above = ceilingAbove(world, cx, y, cz);
			ceiling = Math.min(ceiling, above);
			boolean westSolid = world.solid(cx - 1, y, cz);
			boolean eastSolid = world.solid(cx + 1, y, cz);
			boolean northSolid = world.solid(cx, y, cz - 1);
			boolean southSolid = world.solid(cx, y, cz + 1);
			if (!(westSolid && eastSolid) && !(northSolid && southSolid)) {
				roomMinX = Math.min(roomMinX, cx);
				roomMaxX = Math.max(roomMaxX, cx);
				roomMinZ = Math.min(roomMinZ, cz);
				roomMaxZ = Math.max(roomMaxZ, cz);
				roomCeiling = Math.min(roomCeiling, above);
			}
			for (int dir = 0; dir < 4; dir++) {
				int nx = cx + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
				int nz = cz + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
				boolean solid = dir == 0 ? westSolid : dir == 1 ? eastSolid : dir == 2 ? northSolid : southSolid;
				if (solid) {
					walls++;
					continue;
				}
				int gx = nx - x + MAX_EXTENT;
				int gz = nz - z + MAX_EXTENT;
				if (gx < 0 || gx >= size || gz < 0 || gz >= size) {
					return null;
				}
				int next = index(gx, gz, size);
				if (seen[next]) {
					continue;
				}
				if (!hasCeiling(world, nx, y, nz)) {
					openings++;
					continue;
				}
				seen[next] = true;
				minX = Math.min(minX, nx);
				maxX = Math.max(maxX, nx);
				minZ = Math.min(minZ, nz);
				maxZ = Math.max(maxZ, nz);
				if (tail >= MAX_CELLS || maxX - minX + 1 > MAX_EXTENT || maxZ - minZ + 1 > MAX_EXTENT) {
					return null;
				}
				queue[tail++] = next;
			}
		}
		if (openings > MAX_OPENING_RATIO * (openings + walls)) {
			return null;
		}
		if (roomMinX > roomMaxX) {
			// Every cell is a doorway, as in a one-wide corridor: the whole fill is the room.
			return new Room(minX, minZ, maxX, maxZ, y, ceiling);
		}
		return new Room(roomMinX, roomMinZ, roomMaxX, roomMaxZ, y, roomCeiling);
	}

	private static int index(int gx, int gz, int size) {
		return gz * size + gx;
	}

	/**
	 * Roofs off: everything from the room's lowest ceiling up, over its interior widened by one block to take
	 * in the wall tops and the roof's edge.
	 */
	public static CutawayShape.Box roofBox(Room room) {
		return new CutawayShape.Box(room.minX() - 1, room.ceilingY(), room.minZ() - 1, room.maxX() + 1,
				Integer.MAX_VALUE, room.maxZ() + 1);
	}

	/**
	 * Walls down: the one-block-thick walls of the room on the sides facing a camera at {@code facing}
	 * (see {@link RtsCutawayFocus#quantizeYaw}), from one above the feet layer up to under the lowest
	 * ceiling. The feet-layer row stays as a stub, as in The Sims. Empty when the room is too low.
	 */
	public static List<CutawayShape.Box> nearWalls(Room room, int facing) {
		int bottom = room.floorY() + 1;
		int top = room.ceilingY() - 1;
		if (top < bottom) {
			return List.of();
		}
		int x0 = room.minX() - 1;
		int x1 = room.maxX() + 1;
		int z0 = room.minZ() - 1;
		int z1 = room.maxZ() + 1;
		List<CutawayShape.Box> walls = new ArrayList<>(2);
		int sideX = RtsCutawayFocus.cameraSideX(facing);
		int sideZ = RtsCutawayFocus.cameraSideZ(facing);
		if (sideX != 0) {
			int wx = sideX < 0 ? x0 : x1;
			walls.add(new CutawayShape.Box(wx, bottom, z0, wx, top, z1));
		}
		if (sideZ != 0) {
			int wz = sideZ < 0 ? z0 : z1;
			walls.add(new CutawayShape.Box(x0, bottom, wz, x1, top, wz));
		}
		return List.copyOf(walls);
	}

	/** Whether the hovered block {@code x, y, z} still belongs to {@code room}, for hysteresis. */
	public static boolean near(Room room, int x, int y, int z) {
		return x >= room.minX() - 1 && x <= room.maxX() + 1 && z >= room.minZ() - 1 && z <= room.maxZ() + 1
				&& y >= room.floorY() - 1;
	}
}
