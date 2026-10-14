package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CutawayShadingTest {
	@Test
	void cutWallGetsFixedCapLight() {
		int[] cap = {CutawayShading.CAP_BLOCK, CutawayShading.CAP_SKY};

		assertArrayEquals(cap, CutawayShading.capLight(true, 0, 0));
		assertArrayEquals(cap, CutawayShading.capLight(true, 15, 15));
		assertArrayEquals(cap, CutawayShading.capLight(true, 3, 9));
	}

	@Test
	void floorKeepsRealLightAboveTheFloor() {
		// Dark cave floor: raised to the floor light, sky stays real.
		assertArrayEquals(new int[] {CutawayShading.FLOOR_BLOCK, 0}, CutawayShading.capLight(false, 0, 0));
		// Brighter than the floor light: kept as is.
		assertArrayEquals(new int[] {15, 15}, CutawayShading.capLight(false, 15, 15));
		assertArrayEquals(new int[] {CutawayShading.FLOOR_BLOCK, 9}, CutawayShading.capLight(false, 4, 9));
	}

	@Test
	void capsNeverLookLikeFloors() {
		for (int block = 0; block <= 15; block++) {
			for (int sky = 0; sky <= 15; sky++) {
				int[] cap = CutawayShading.capLight(true, block, sky);
				int[] floor = CutawayShading.capLight(false, block, sky);
				assertFalse(Arrays.equals(cap, floor), block + "/" + sky);
			}
		}
	}
}
