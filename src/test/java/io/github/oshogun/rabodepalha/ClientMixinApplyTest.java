package io.github.oshogun.rabodepalha;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import org.junit.jupiter.api.Test;

/**
 * Applies every client mixin the way the game does at startup, so a broken injection fails the build
 * instead of crashing the first person to launch the client. Runs without Sodium; see
 * {@link SodiumMixinApplyTest} for the Sodium mixins.
 */
class ClientMixinApplyTest {
	static final String CLIENT_CONFIG = "rabodepalha.client.mixins.json";

	@Test
	void everyClientMixinApplies() throws IOException {
		FabricLoader loader = FabricLoader.getInstance();
		assertTrue(loader.getEnvironmentType() == EnvType.CLIENT, "client mixins only apply on the client side");
		assertTrue(loader.isModLoaded(RaboDePalha.MOD_ID), "rabodepalha is not loaded as a mod");
		assertFalse(loader.isModLoaded("sodium"), "the vanilla mixin test must run without Sodium");

		ClassLoader classLoader = ClientMixinApplyTest.class.getClassLoader();
		Map<String, List<String>> mixins = MixinTargets.of(CLIENT_CONFIG, classLoader);
		assertTrue(mixins.containsKey("io.github.oshogun.rabodepalha.client.mixin.LevelExtractorCutawayMixin"),
				"misread " + CLIENT_CONFIG + ": " + mixins.keySet());
		assertAll(MixinTargets.loadChecks(mixins, classLoader));
	}
}
