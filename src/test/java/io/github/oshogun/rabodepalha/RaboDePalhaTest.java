package io.github.oshogun.rabodepalha;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

class RaboDePalhaTest {
	@Test
	void constantIsRabodepalha() {
		assertEquals("rabodepalha", RaboDePalha.MOD_ID);
	}

	@Test
	void idUsesModNamespace() {
		Identifier id = RaboDePalha.id("x");
		assertEquals("rabodepalha", id.getNamespace());
		assertEquals("x", id.getPath());
	}
}
