package io.github.oshogun.rabodepalha.client.cutaway.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

/**
 * The only door from the cutaway into Sodium's API. Only call it when Sodium is loaded: the class links
 * against Sodium and fails to load without it.
 */
public final class SodiumCutaway {
	private SodiumCutaway() {
	}

	/** Makes Sodium rebuild its visible-section list on the next frame. */
	public static void invalidate() {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer != null) {
			renderer.scheduleTerrainUpdate();
		}
	}
}
