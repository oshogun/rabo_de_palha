package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.oshogun.rabodepalha.rts.BlueprintCatalog.Entry;
import io.github.oshogun.rabodepalha.rts.BlueprintCatalog.Listing;
import io.github.oshogun.rabodepalha.rts.BlueprintCatalog.Source;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

class BlueprintCatalogTest {
	private static Identifier id(String namespace, String path) {
		return Identifier.fromNamespaceAndPath(namespace, path);
	}

	private static Map<Identifier, Source> sample() {
		Map<Identifier, Source> classified = new HashMap<>();
		classified.put(id("minecraft", "village/house"), Source.VANILLA);
		classified.put(id("minecraft", "bastion/bridge"), Source.VANILLA);
		classified.put(id("mymod", "tower"), Source.PACK);
		classified.put(id("amod", "hut"), Source.PACK);
		classified.put(id("minecraft", "my_house"), Source.WORLD);
		classified.put(id("minecraft", "a_shed"), Source.WORLD);
		return classified;
	}

	@Test
	void ordersWorldThenPackThenVanillaSortedById() {
		Listing listing = BlueprintCatalog.select(sample(), true, 100);
		assertEquals(List.of(
				new Entry(id("minecraft", "a_shed"), Source.WORLD),
				new Entry(id("minecraft", "my_house"), Source.WORLD),
				new Entry(id("amod", "hut"), Source.PACK),
				new Entry(id("mymod", "tower"), Source.PACK),
				new Entry(id("minecraft", "bastion/bridge"), Source.VANILLA),
				new Entry(id("minecraft", "village/house"), Source.VANILLA)), listing.entries());
		assertEquals(0, listing.more());
	}

	@Test
	void hidesVanillaButKeepsMinecraftNamespacedWorldEntries() {
		Listing listing = BlueprintCatalog.select(sample(), false, 100);
		assertEquals(List.of(
				new Entry(id("minecraft", "a_shed"), Source.WORLD),
				new Entry(id("minecraft", "my_house"), Source.WORLD),
				new Entry(id("amod", "hut"), Source.PACK),
				new Entry(id("mymod", "tower"), Source.PACK)), listing.entries());
		assertEquals(0, listing.more());
	}

	@Test
	void capAppliesAfterFilteringAndCountsTheRest() {
		Listing hidden = BlueprintCatalog.select(sample(), false, 3);
		assertEquals(List.of(
				new Entry(id("minecraft", "a_shed"), Source.WORLD),
				new Entry(id("minecraft", "my_house"), Source.WORLD),
				new Entry(id("amod", "hut"), Source.PACK)), hidden.entries());
		assertEquals(1, hidden.more());

		Listing shown = BlueprintCatalog.select(sample(), true, 2);
		assertEquals(List.of(
				new Entry(id("minecraft", "a_shed"), Source.WORLD),
				new Entry(id("minecraft", "my_house"), Source.WORLD)), shown.entries());
		assertEquals(4, shown.more());
	}

	@Test
	void emptyCatalogGivesAnEmptyListing() {
		Listing listing = BlueprintCatalog.select(Map.of(), true, BlueprintCatalog.MAX_BLUEPRINTS);
		assertEquals(List.of(), listing.entries());
		assertEquals(0, listing.more());
	}
}
