package io.github.oshogun.rabodepalha.rts;

/**
 * Pure rules for the RTS camera's brightness boost. While RTS mode is active the client raises the lightmap's
 * night-vision intensity to the effective {@link Preset}, so caves and the world at night are readable without
 * a mob effect or any server change. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>The lightmap shader uses night vision as an ambient floor, {@code max(ambient, nightVisionColor *
 * intensity)}, and adds sky and block light on top before clamping. With the default night-vision colour (0.6
 * grey) a cut cap at block light 7 gains about 0.25 over the floor and a real floor at light 12 or more about
 * 0.7 (see {@link CutawayShading}). The presets stay below 1.0 so the floor stays low enough that caps still
 * read darker than floors at every strength.
 */
public final class RtsBrightness {
	/** The preset used in level mode until the player picks one with the key. */
	public static final Preset DEFAULT_LEVEL = Preset.MEDIUM;

	private RtsBrightness() {
	}

	/** A boost strength, as a night-vision intensity in {@code [0, 1)}. */
	public enum Preset {
		OFF(0.0f),
		LOW(0.35f),
		MEDIUM(0.6f),
		HIGH(0.85f);

		public final float intensity;

		Preset(float intensity) {
			this.intensity = intensity;
		}
	}

	/** The preset after {@code current} in the key's cycle, OFF, LOW, MEDIUM, HIGH and back to OFF. */
	public static Preset next(Preset current) {
		Preset[] values = Preset.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	/**
	 * The preset in effect: OFF outside RTS mode, the player's {@code chosen} preset when there is one, and
	 * otherwise {@link #DEFAULT_LEVEL} in level mode and OFF in the surface view.
	 */
	public static Preset effective(Preset chosen, boolean rtsActive, boolean levelMode) {
		if (!rtsActive) {
			return Preset.OFF;
		}
		if (chosen != null) {
			return chosen;
		}
		return levelMode ? DEFAULT_LEVEL : Preset.OFF;
	}

	/**
	 * The choice after one key press in RTS mode: one step on from what is in effect, so the first press of a
	 * session moves away from the mode default the player sees.
	 */
	public static Preset press(Preset chosen, boolean levelMode) {
		return next(effective(chosen, true, levelMode));
	}

	/** The night-vision intensity with the boost applied; never lower than vanilla's, so real night vision wins. */
	public static float boostedIntensity(float vanilla, float boost) {
		return Math.max(vanilla, boost);
	}
}
