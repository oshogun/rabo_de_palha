package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RtsOrderColorsTest {
	@Test
	void everyKindHasAnOpaqueLineAndTranslucentFill() {
		for (RtsTaskKind kind : RtsTaskKind.values()) {
			int line = RtsOrderColors.line(kind);
			int fill = RtsOrderColors.fill(kind);
			assertEquals(0xFF, line >>> 24, kind + " line alpha");
			assertTrue((fill >>> 24) > 0 && (fill >>> 24) < 0xFF, kind + " fill alpha");
			assertEquals(line & 0xFFFFFF, fill & 0xFFFFFF, kind + " fill RGB");
		}
	}

	@Test
	void mainKindsAreDistinct() {
		Set<Integer> colours = new HashSet<>();
		for (RtsTaskKind kind : List.of(RtsTaskKind.MOVE, RtsTaskKind.MINE, RtsTaskKind.PLACE, RtsTaskKind.DIG,
				RtsTaskKind.BUILD, RtsTaskKind.DEPOSIT, RtsTaskKind.WITHDRAW, RtsTaskKind.USE, RtsTaskKind.PLANT, RtsTaskKind.HARVEST,
				RtsTaskKind.TEND, RtsTaskKind.OTHER)) {
			assertTrue(colours.add(RtsOrderColors.line(kind)), kind + " shares a colour");
		}
	}

	@Test
	void digMatchesTheDigAreaBlue() {
		assertEquals(0xFF4AA8FF, RtsOrderColors.line(RtsTaskKind.DIG));
	}

	@Test
	void dimmedKeepsRgbAndLowersAlpha() {
		for (RtsTaskKind kind : RtsTaskKind.values()) {
			int line = RtsOrderColors.line(kind);
			int dimmed = RtsOrderColors.dimmed(line);
			assertEquals(line & 0xFFFFFF, dimmed & 0xFFFFFF, kind + " RGB");
			assertTrue((dimmed >>> 24) < (line >>> 24) && (dimmed >>> 24) > 0, kind + " alpha");
		}
		// Never raises an already low alpha.
		assertEquals(0x10123456, RtsOrderColors.dimmed(0x10123456));
	}
}
