package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.oshogun.rabodepalha.rts.RtsBrightness.Preset;

class RtsBrightnessTest {
	private static final float EPS = 1e-6f;

	@Test
	void cycleGoesUpAndWrapsToOff() {
		assertEquals(Preset.LOW, RtsBrightness.next(Preset.OFF));
		assertEquals(Preset.MEDIUM, RtsBrightness.next(Preset.LOW));
		assertEquals(Preset.HIGH, RtsBrightness.next(Preset.MEDIUM));
		assertEquals(Preset.OFF, RtsBrightness.next(Preset.HIGH));
	}

	@Test
	void defaultIsOnInLevelModeAndOffOnTheSurface() {
		assertEquals(RtsBrightness.DEFAULT_LEVEL, RtsBrightness.effective(null, true, true));
		assertEquals(Preset.MEDIUM, RtsBrightness.effective(null, true, true));
		assertEquals(Preset.OFF, RtsBrightness.effective(null, true, false));
	}

	@Test
	void choiceOverridesBothModes() {
		assertEquals(Preset.OFF, RtsBrightness.effective(Preset.OFF, true, true));
		assertEquals(Preset.HIGH, RtsBrightness.effective(Preset.HIGH, true, false));
		assertEquals(Preset.LOW, RtsBrightness.effective(Preset.LOW, true, true));
	}

	@Test
	void inactiveRtsIsAlwaysOff() {
		assertEquals(Preset.OFF, RtsBrightness.effective(null, false, true));
		assertEquals(Preset.OFF, RtsBrightness.effective(null, false, false));
		for (Preset chosen : Preset.values()) {
			assertEquals(Preset.OFF, RtsBrightness.effective(chosen, false, true));
			assertEquals(Preset.OFF, RtsBrightness.effective(chosen, false, false));
		}
	}

	@Test
	void firstPressStepsFromTheEffectivePreset() {
		assertEquals(Preset.HIGH, RtsBrightness.press(null, true));
		assertEquals(Preset.LOW, RtsBrightness.press(null, false));
		assertEquals(Preset.OFF, RtsBrightness.press(Preset.HIGH, false));
		assertEquals(Preset.LOW, RtsBrightness.press(Preset.OFF, true));
	}

	@Test
	void boostNeverLowersVanilla() {
		assertEquals(0.6f, RtsBrightness.boostedIntensity(0.0f, 0.6f), EPS);
		assertEquals(1.0f, RtsBrightness.boostedIntensity(1.0f, 0.6f), EPS);
		assertEquals(0.4f, RtsBrightness.boostedIntensity(0.4f, 0.0f), EPS);
		for (Preset preset : Preset.values()) {
			for (float vanilla : new float[] {0.0f, 0.2f, 0.5f, 0.9f, 1.0f}) {
				float boosted = RtsBrightness.boostedIntensity(vanilla, preset.intensity);
				assertTrue(boosted >= vanilla);
				assertEquals(Math.max(vanilla, preset.intensity), boosted, EPS);
			}
		}
	}

	@Test
	void presetsIncreaseFromZeroAndStayBelowOne() {
		assertEquals(0.0f, Preset.OFF.intensity);
		Preset[] values = Preset.values();
		for (int i = 1; i < values.length; i++) {
			assertTrue(values[i].intensity > values[i - 1].intensity, values[i] + " not above " + values[i - 1]);
		}
		for (Preset preset : values) {
			assertTrue(preset.intensity < 1.0f, preset + " reaches 1.0");
		}
	}
}
