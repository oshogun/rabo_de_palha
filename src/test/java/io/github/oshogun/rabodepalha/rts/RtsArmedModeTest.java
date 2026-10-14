package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsArmedModeTest {
	@Test
	void validClickFiresAndDisarms() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("mine");

		assertEquals("mine", state.armed());
		assertEquals("mine", state.onLeftClick(true, false));
		assertNull(state.armed());
	}

	@Test
	void clickWithNothingArmedReturnsNull() {
		RtsArmedMode<String> state = new RtsArmedMode<>();

		assertNull(state.onLeftClick(true, false));
		assertNull(state.onLeftClick(true, true));
		assertNull(state.onLeftClick(false, false));
		assertNull(state.armed());
	}

	@Test
	void shiftClickFiresAndStaysArmed() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("place");

		for (int i = 0; i < 3; i++) {
			assertEquals("place", state.onLeftClick(true, true));
			assertEquals("place", state.armed());
		}
		assertEquals("place", state.onLeftClick(true, false));
		assertNull(state.armed());
	}

	@Test
	void invalidClickKeepsTheModeArmed() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("spawn");

		assertNull(state.onLeftClick(false, false));
		assertEquals("spawn", state.armed());
		assertNull(state.onLeftClick(false, true));
		assertEquals("spawn", state.armed());

		assertEquals("spawn", state.onLeftClick(true, false));
		assertNull(state.armed());
	}

	@Test
	void rightClickDisarms() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		assertFalse(state.onRightClick());

		state.arm("move");
		assertTrue(state.onRightClick());
		assertNull(state.armed());
		assertFalse(state.onRightClick());
	}

	@Test
	void escapeDisarms() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		assertFalse(state.onEscape());

		state.arm("move");
		assertTrue(state.onEscape());
		assertNull(state.armed());
		assertFalse(state.onEscape());
	}

	@Test
	void armingTheSameModeTogglesItOff() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("mine");
		state.arm("mine");

		assertNull(state.armed());
	}

	@Test
	void armingAnotherModeReplacesIt() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("mine");
		state.arm("place");

		assertEquals("place", state.armed());
		assertEquals("place", state.onLeftClick(true, false));
	}

	@Test
	void disarmClearsTheMode() {
		RtsArmedMode<String> state = new RtsArmedMode<>();
		state.arm("stop");
		state.disarm();

		assertNull(state.armed());
		assertNull(state.onLeftClick(true, false));
	}
}
