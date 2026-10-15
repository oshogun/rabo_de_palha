package io.github.oshogun.rabodepalha;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Applies the Sodium cutaway mixins, and the vanilla client mixins next to them, with Sodium loaded.
 * Runs only in the {@code sodiumTest} task, which adds Sodium to the classpath.
 */
@Tag("sodium")
class SodiumMixinApplyTest {
	private static final String SODIUM_CONFIG = "rabodepalha.sodium.mixins.json";

	@Test
	void everyMixinAppliesWithSodium() throws IOException {
		assertTrue(FabricLoader.getInstance().isModLoaded("sodium"), "Sodium is not loaded; run this in sodiumTest");

		ClassLoader classLoader = SodiumMixinApplyTest.class.getClassLoader();
		Map<String, List<String>> sodiumMixins = MixinTargets.of(SODIUM_CONFIG, classLoader);
		assertFalse(sodiumMixins.isEmpty(), "misread " + SODIUM_CONFIG);
		Map<String, List<String>> mixins = new LinkedHashMap<>(sodiumMixins);
		mixins.putAll(MixinTargets.of(ClientMixinApplyTest.CLIENT_CONFIG, classLoader));
		assertAll(MixinTargets.loadChecks(mixins, classLoader));
	}
}
