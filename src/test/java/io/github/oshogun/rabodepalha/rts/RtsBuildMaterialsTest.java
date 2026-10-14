package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RtsBuildMaterialsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Item components are bound when datapacks load; a unit test binds the minimum ItemStack needs.
		for (Item item : List.of(Items.STONE, Items.OAK_PLANKS, Items.DIRT)) {
			Holder.Reference<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> ref ? ref : null;
			if (holder != null && !holder.areComponentsBound()) {
				holder.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
			}
		}
	}

	private static Map<Item, Integer> needed() {
		Map<Item, Integer> needed = new LinkedHashMap<>();
		needed.put(Items.STONE, 10);
		needed.put(Items.OAK_PLANKS, 4);
		return needed;
	}

	@Test
	void rowsFollowTheNeededOrderWithTheBestSingleWorker() {
		List<List<ItemStack>> inventories = List.of(
				List.of(new ItemStack(Items.STONE, 6), new ItemStack(Items.STONE, 5), new ItemStack(Items.OAK_PLANKS, 1)),
				List.of(new ItemStack(Items.STONE, 3), new ItemStack(Items.OAK_PLANKS, 4)));

		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(needed(), inventories);

		assertEquals(List.of(
				new RtsBuildMaterials.Row(Items.STONE, 10, 11),
				new RtsBuildMaterials.Row(Items.OAK_PLANKS, 4, 4)), report.rows());
		assertFalse(report.rows().get(0).missing());
		assertEquals(0, report.workersReady());
		assertEquals(2, report.workersChecked());
	}

	@Test
	void countsWorkersThatCarryEverything() {
		List<List<ItemStack>> inventories = List.of(
				List.of(new ItemStack(Items.STONE, 10), new ItemStack(Items.OAK_PLANKS, 4)),
				List.of(new ItemStack(Items.STONE, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.DIRT, 1)),
				List.of(new ItemStack(Items.STONE, 9), new ItemStack(Items.OAK_PLANKS, 4)));

		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(needed(), inventories);

		assertEquals(2, report.workersReady());
		assertEquals(3, report.workersChecked());
	}

	@Test
	void emptyInventoriesHaveNothing() {
		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(needed(), List.of(List.of(), List.of(ItemStack.EMPTY)));

		assertTrue(report.rows().stream().allMatch(row -> row.bestHave() == 0 && row.missing()));
		assertEquals(0, report.workersReady());
		assertEquals(2, report.workersChecked());
	}

	@Test
	void noWorkersChecked() {
		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(needed(), List.of());

		assertEquals(0, report.workersChecked());
		assertEquals(0, report.workersReady());
		assertTrue(report.rows().stream().allMatch(RtsBuildMaterials.Row::missing));
	}

	@Test
	void itemsSplitAcrossWorkersDoNotMakeAWorkerReady() {
		List<List<ItemStack>> inventories = List.of(
				List.of(new ItemStack(Items.STONE, 10)),
				List.of(new ItemStack(Items.OAK_PLANKS, 4)));

		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(needed(), inventories);

		assertTrue(report.rows().stream().noneMatch(RtsBuildMaterials.Row::missing));
		assertEquals(0, report.workersReady());
	}

	@Test
	void nothingNeededMakesEveryWorkerReady() {
		RtsBuildMaterials.Report report = RtsBuildMaterials.compare(Map.of(), List.of(List.of(), List.of()));

		assertTrue(report.rows().isEmpty());
		assertEquals(2, report.workersReady());
	}
}
