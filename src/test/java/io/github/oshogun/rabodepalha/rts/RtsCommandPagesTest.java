package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RtsCommandPagesTest {
	/**
	 * The bar's commands by id and page, mirroring {@code RtsCommands.COMMANDS} (a client class): keep the two in
	 * step.
	 */
	private static final List<String> REGISTRY = List.of("move", "mine", "place", "dig", "stop", "spawn", "deposit",
			"withdraw", "build");
	private static final Map<String, RtsCommandPage> PAGE_OF = Map.of(
			"move", RtsCommandPage.ORDERS,
			"mine", RtsCommandPage.ORDERS,
			"place", RtsCommandPage.ORDERS,
			"dig", RtsCommandPage.ORDERS,
			"stop", RtsCommandPage.GLOBAL,
			"spawn", RtsCommandPage.ORDERS,
			"deposit", RtsCommandPage.HAUL_BUILD,
			"withdraw", RtsCommandPage.HAUL_BUILD,
			"build", RtsCommandPage.HAUL_BUILD);

	private static RtsCommandPages<String> registry() {
		return new RtsCommandPages<>(REGISTRY, PAGE_OF::get);
	}

	@Test
	void registryGroupsTheCurrentCommands() {
		RtsCommandPages<String> pages = registry();
		assertEquals(List.of("move", "mine", "place", "dig", "spawn"), pages.commands(RtsCommandPage.ORDERS));
		assertEquals(List.of("deposit", "withdraw", "build"), pages.commands(RtsCommandPage.HAUL_BUILD));
		assertEquals(List.of("stop"), pages.globals());
	}

	@Test
	void emptyPagesAreHidden() {
		RtsCommandPages<String> pages = registry();
		assertEquals(List.of(RtsCommandPage.ORDERS, RtsCommandPage.HAUL_BUILD), pages.visiblePages());
		for (RtsCommandPage empty : List.of(RtsCommandPage.FARM, RtsCommandPage.GATHER, RtsCommandPage.MAKE,
				RtsCommandPage.WORKER)) {
			assertTrue(pages.commands(empty).isEmpty(), empty.id());
			assertEquals(Optional.empty(), pages.slot(empty, 1), empty.id());
		}
		assertEquals(RtsCommandPage.ORDERS, pages.first());
		assertEquals(RtsCommandPage.ORDERS, pages.valid(RtsCommandPage.FARM));
		assertEquals(RtsCommandPage.HAUL_BUILD, pages.valid(RtsCommandPage.HAUL_BUILD));
	}

	@Test
	void slotKeysPressTheButtonInThatSlotOnEachPage() {
		RtsCommandPages<String> pages = registry();
		List<String> orders = List.of("move", "mine", "place", "dig", "spawn");
		for (int slot = 1; slot <= orders.size(); slot++) {
			assertEquals(Optional.of(orders.get(slot - 1)), pages.slot(RtsCommandPage.ORDERS, slot), "orders " + slot);
		}
		assertEquals(Optional.of("deposit"), pages.slot(RtsCommandPage.HAUL_BUILD, 1));
		assertEquals(Optional.of("withdraw"), pages.slot(RtsCommandPage.HAUL_BUILD, 2));
		assertEquals(Optional.of("build"), pages.slot(RtsCommandPage.HAUL_BUILD, 3));
		// Past the page's size, and outside 1..9, there is nothing.
		for (int slot = 6; slot <= 9; slot++) {
			assertEquals(Optional.empty(), pages.slot(RtsCommandPage.ORDERS, slot), "orders " + slot);
		}
		assertEquals(Optional.empty(), pages.slot(RtsCommandPage.HAUL_BUILD, 4));
		assertEquals(Optional.empty(), pages.slot(RtsCommandPage.ORDERS, 0));
		assertEquals(Optional.empty(), pages.slot(RtsCommandPage.ORDERS, 10));
		assertEquals(Optional.empty(), pages.slot(RtsCommandPage.ORDERS, -1));
		// Global commands have no slot.
		assertEquals(Optional.empty(), pages.slot(RtsCommandPage.GLOBAL, 1));
	}

	@Test
	void slotOfRoundTrips() {
		RtsCommandPages<String> pages = registry();
		for (RtsCommandPage page : pages.visiblePages()) {
			for (int slot = 1; slot <= pages.commands(page).size(); slot++) {
				String command = pages.slot(page, slot).orElseThrow();
				assertEquals(slot, pages.slotOf(page, command), page.id() + " " + command);
			}
		}
		assertEquals(0, pages.slotOf(RtsCommandPage.ORDERS, "build"));
		assertEquals(0, pages.slotOf(RtsCommandPage.ORDERS, "stop"));
		assertEquals(0, pages.slotOf(RtsCommandPage.GLOBAL, "stop"));
	}

	@Test
	void nextAndPreviousWrapAndSkipEmptyPages() {
		// Orders, Make and Worker have commands; Haul and build, Farm and Gather don't.
		Map<String, RtsCommandPage> pageOf = Map.of("a", RtsCommandPage.ORDERS, "b", RtsCommandPage.MAKE,
				"c", RtsCommandPage.WORKER, "s", RtsCommandPage.GLOBAL);
		RtsCommandPages<String> pages = new RtsCommandPages<>(List.of("a", "b", "c", "s"), pageOf::get);
		assertEquals(List.of(RtsCommandPage.ORDERS, RtsCommandPage.MAKE, RtsCommandPage.WORKER), pages.visiblePages());
		assertEquals(RtsCommandPage.MAKE, pages.next(RtsCommandPage.ORDERS));
		assertEquals(RtsCommandPage.WORKER, pages.next(RtsCommandPage.MAKE));
		assertEquals(RtsCommandPage.ORDERS, pages.next(RtsCommandPage.WORKER));
		assertEquals(RtsCommandPage.WORKER, pages.previous(RtsCommandPage.ORDERS));
		assertEquals(RtsCommandPage.ORDERS, pages.previous(RtsCommandPage.MAKE));
		// A hidden page moves to the first shown one.
		assertEquals(RtsCommandPage.ORDERS, pages.next(RtsCommandPage.FARM));
		assertEquals(RtsCommandPage.ORDERS, pages.previous(RtsCommandPage.GLOBAL));

		RtsCommandPages<String> two = registry();
		assertEquals(RtsCommandPage.HAUL_BUILD, two.next(RtsCommandPage.ORDERS));
		assertEquals(RtsCommandPage.ORDERS, two.next(RtsCommandPage.HAUL_BUILD));
		assertEquals(RtsCommandPage.HAUL_BUILD, two.previous(RtsCommandPage.ORDERS));
	}

	@Test
	void aSinglePageCyclesToItself() {
		RtsCommandPages<String> pages = new RtsCommandPages<>(List.of("a"), c -> RtsCommandPage.FARM);
		assertEquals(List.of(RtsCommandPage.FARM), pages.visiblePages());
		assertEquals(RtsCommandPage.FARM, pages.first());
		assertEquals(RtsCommandPage.FARM, pages.next(RtsCommandPage.FARM));
		assertEquals(RtsCommandPage.FARM, pages.previous(RtsCommandPage.FARM));
	}

	@Test
	void aPageHoldsAtMostNineCommands() {
		List<String> nine = new ArrayList<>();
		for (int i = 0; i < RtsCommandPage.MAX_SLOTS; i++) {
			nine.add("c" + i);
		}
		RtsCommandPages<String> full = new RtsCommandPages<>(nine, c -> RtsCommandPage.FARM);
		assertEquals(Optional.of("c8"), full.slot(RtsCommandPage.FARM, 9));

		List<String> ten = new ArrayList<>(nine);
		ten.add("c9");
		assertThrows(IllegalStateException.class, () -> new RtsCommandPages<>(ten, c -> RtsCommandPage.FARM));
		// Globals aren't on a page, so they have no slot cap.
		new RtsCommandPages<>(ten, c -> RtsCommandPage.GLOBAL);
	}

	@Test
	void stopIsReachableFromEveryPage() {
		RtsCommandPages<String> pages = registry();
		assertTrue(pages.globals().contains("stop"));
		for (RtsCommandPage page : pages.visiblePages()) {
			// The bar shows the page's commands, then the globals.
			List<String> bar = new ArrayList<>(pages.commands(page));
			bar.addAll(pages.globals());
			assertTrue(bar.contains("stop"), page.id());
			assertEquals(0, pages.slotOf(page, "stop"), page.id());
		}
	}

	@Test
	void noPagesFallsBackToOrders() {
		RtsCommandPages<String> pages = new RtsCommandPages<>(List.of("stop"), c -> RtsCommandPage.GLOBAL);
		assertTrue(pages.visiblePages().isEmpty());
		assertEquals(RtsCommandPage.ORDERS, pages.first());
		assertEquals(List.of("stop"), pages.globals());
	}

	@Test
	void pagesHaveLangKeys() {
		assertEquals("rabodepalha.rts.page.orders", RtsCommandPage.ORDERS.langKey());
		assertEquals("rabodepalha.rts.page.haul_build", RtsCommandPage.HAUL_BUILD.langKey());
	}
}
