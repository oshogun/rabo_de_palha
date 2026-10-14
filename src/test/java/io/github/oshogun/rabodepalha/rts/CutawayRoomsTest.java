package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CutawayRoomsTest {
	/**
	 * A small block world: {@code rock} makes every block solid unless carved out as air, otherwise only
	 * the ground (y below 0) and the placed blocks are. Leaves are ceilings but not walls.
	 */
	private static final class World implements CutawayRooms.Solidity {
		private final boolean rock;
		private final Set<List<Integer>> solid = new HashSet<>();
		private final Set<List<Integer>> air = new HashSet<>();
		private final Set<List<Integer>> leaves = new HashSet<>();

		World(boolean rock) {
			this.rock = rock;
		}

		World fill(int x0, int y0, int z0, int x1, int y1, int z1) {
			return each(solid, x0, y0, z0, x1, y1, z1);
		}

		World carve(int x0, int y0, int z0, int x1, int y1, int z1) {
			each(solid, x0, y0, z0, x1, y1, z1, true);
			return each(air, x0, y0, z0, x1, y1, z1);
		}

		World leaves(int x0, int y0, int z0, int x1, int y1, int z1) {
			return each(leaves, x0, y0, z0, x1, y1, z1);
		}

		private World each(Set<List<Integer>> set, int x0, int y0, int z0, int x1, int y1, int z1) {
			return each(set, x0, y0, z0, x1, y1, z1, false);
		}

		private World each(Set<List<Integer>> set, int x0, int y0, int z0, int x1, int y1, int z1, boolean remove) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						if (remove) {
							set.remove(List.of(x, y, z));
						} else {
							set.add(List.of(x, y, z));
						}
					}
				}
			}
			return this;
		}

		@Override
		public boolean solid(int x, int y, int z) {
			List<Integer> key = List.of(x, y, z);
			if (air.contains(key)) {
				return false;
			}
			return rock || y < 0 || solid.contains(key);
		}

		@Override
		public boolean ceiling(int x, int y, int z) {
			return solid(x, y, z) || leaves.contains(List.of(x, y, z));
		}
	}

	/**
	 * A house with a 5x5 interior (x and z 1..5), walls at 0 and 6 from y 0 to 2, a roof at y 3 over the
	 * walls and a two-high open door at (3, 0..1, 0).
	 */
	private static World house() {
		return new World(false)
				.fill(0, 0, 0, 6, 2, 6)
				.carve(1, 0, 1, 5, 2, 5)
				.fill(0, 3, 0, 6, 3, 6)
				.carve(3, 0, 0, 3, 1, 0);
	}

	@Test
	void openFieldIsNoRoom() {
		World field = new World(false);

		assertNull(CutawayRooms.detect(field, 0, 0, 0));
		// Hovering the grass: the air above has no ceiling and the probe finds only ground.
		assertNull(CutawayRooms.start(field, 0, -1, 0, 0, 1, 0));
	}

	@Test
	void houseIsARoom() {
		CutawayRooms.Room room = CutawayRooms.detect(house(), 3, 0, 3);

		assertNotNull(room);
		// The open door doesn't widen the room into the wall, nor lower its ceiling to the lintel.
		assertEquals(new CutawayRooms.Room(1, 1, 5, 5, 0, 3), room);
	}

	@Test
	void houseRoofBoxCoversTheRoofAndWallTops() {
		CutawayRooms.Room room = CutawayRooms.detect(house(), 2, 0, 4);
		CutawayShape.Box roof = CutawayRooms.roofBox(room);

		assertEquals(new CutawayShape.Box(0, 3, 0, 6, Integer.MAX_VALUE, 6), roof);
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				assertTrue(roof.contains(x, 3, z));
			}
		}
		// The room itself, its walls and a worker standing in it stay.
		assertTrue(!roof.contains(3, 2, 3) && !roof.contains(0, 2, 0) && !roof.contains(3, 0, 3));
	}

	@Test
	void nearWallsFaceTheCamera() {
		CutawayRooms.Room room = CutawayRooms.detect(house(), 3, 0, 3);

		// Looking south (+z) the camera is north, at -z: the z=0 wall comes down, above the feet row.
		assertEquals(List.of(new CutawayShape.Box(0, 1, 0, 6, 2, 0)), CutawayRooms.nearWalls(room, 0));
		// Looking west, the camera is east: the x=6 wall.
		assertEquals(List.of(new CutawayShape.Box(6, 1, 0, 6, 2, 6)), CutawayRooms.nearWalls(room, 2));
		assertEquals(List.of(new CutawayShape.Box(0, 1, 6, 6, 2, 6)), CutawayRooms.nearWalls(room, 4));
		assertEquals(List.of(new CutawayShape.Box(0, 1, 0, 0, 2, 6)), CutawayRooms.nearWalls(room, 6));
		// Diagonals take two walls.
		assertEquals(List.of(new CutawayShape.Box(6, 1, 0, 6, 2, 6), new CutawayShape.Box(0, 1, 0, 6, 2, 0)),
				CutawayRooms.nearWalls(room, 1));
		assertEquals(List.of(new CutawayShape.Box(0, 1, 0, 0, 2, 6), new CutawayShape.Box(0, 1, 6, 6, 2, 6)),
				CutawayRooms.nearWalls(room, 5));
	}

	@Test
	void lowRoomHasNoWallsToTakeDown() {
		// A one-high crawlspace has only the feet row, which stays as the stub.
		assertEquals(List.of(), CutawayRooms.nearWalls(new CutawayRooms.Room(1, 1, 3, 3, 0, 1), 0));
		// Two high: the head row comes down.
		assertEquals(List.of(new CutawayShape.Box(0, 1, 0, 4, 1, 0)),
				CutawayRooms.nearWalls(new CutawayRooms.Room(1, 1, 3, 3, 0, 2), 0));
	}

	@Test
	void hoveringTheRoofFindsTheRoomUnderIt() {
		World house = house();

		CutawayRooms.Cell start = CutawayRooms.start(house, 3, 3, 3, 0, 1, 0);

		assertEquals(new CutawayRooms.Cell(3, 0, 3), start);
		assertEquals(CutawayRooms.detect(house, 3, 0, 3), CutawayRooms.detect(house, start.x(), start.y(), start.z()));
	}

	@Test
	void hoveringTheFloorOrAWallFromInsideStartsInTheRoom() {
		World house = house();

		assertEquals(new CutawayRooms.Cell(2, 0, 2), CutawayRooms.start(house, 2, -1, 2, 0, 1, 0));
		// The inside of the east wall at head height: the cell in front of it, dropped to the floor.
		assertEquals(new CutawayRooms.Cell(5, 0, 3), CutawayRooms.start(house, 6, 2, 3, -1, 0, 0));
	}

	@Test
	void hoveringTheOutsideOfAWallIsNoRoom() {
		assertNull(CutawayRooms.start(house(), 6, 1, 3, 1, 0, 0));
	}

	@Test
	void cliffOverhangIsNoRoom() {
		// A cliff face at z = -1 with a 10x5 overhang sticking out at y 3; the sides and front are open.
		World overhang = new World(false)
				.fill(0, 0, -1, 9, 3, -1)
				.fill(0, 3, 0, 9, 3, 4);

		assertNull(CutawayRooms.detect(overhang, 4, 0, 2));
	}

	@Test
	void forestCanopyIsNoRoom() {
		// Leaves 4 up over a wide area, a few trunks: big and open, so no room.
		World forest = new World(false).leaves(-20, 4, -20, 20, 5, 20);
		for (int x = -18; x <= 18; x += 6) {
			for (int z = -18; z <= 18; z += 6) {
				forest.fill(x, 0, z, x, 3, z);
			}
		}

		assertNull(CutawayRooms.detect(forest, 1, 0, 1));
	}

	@Test
	void largeCaveIsLeftToTheViewLevel() {
		World cave = new World(true).carve(0, 0, 0, 39, 2, 39);

		assertNull(CutawayRooms.detect(cave, 20, 0, 20));
	}

	@Test
	void smallCavePocketIsARoom() {
		World cave = new World(true).carve(0, 0, 0, 3, 2, 3);

		assertEquals(new CutawayRooms.Room(0, 0, 3, 3, 0, 3), CutawayRooms.detect(cave, 1, 0, 2));
	}

	@Test
	void twoStoreyHouseCutsFromTheFirstCeiling() {
		World house = new World(false)
				.fill(0, 0, 0, 6, 6, 6)
				.carve(1, 0, 1, 5, 2, 5)
				.carve(1, 4, 1, 5, 6, 5)
				.fill(0, 7, 0, 6, 7, 6);

		CutawayRooms.Room ground = CutawayRooms.detect(house, 3, 0, 3);

		assertEquals(new CutawayRooms.Room(1, 1, 5, 5, 0, 3), ground);
		assertEquals(3, CutawayRooms.roofBox(ground).minY());
		// Upstairs is its own room under the real roof.
		assertEquals(new CutawayRooms.Room(1, 1, 5, 5, 4, 7), CutawayRooms.detect(house, 3, 4, 3));
	}

	@Test
	void hoveredBlockNearTheRoomKeepsIt() {
		CutawayRooms.Room room = new CutawayRooms.Room(1, 1, 5, 5, 0, 3);

		assertTrue(CutawayRooms.near(room, 3, -1, 3));
		assertTrue(CutawayRooms.near(room, 0, 1, 6));
		assertTrue(!CutawayRooms.near(room, 7, 0, 3));
		assertTrue(!CutawayRooms.near(room, 3, -2, 3));
	}
}
