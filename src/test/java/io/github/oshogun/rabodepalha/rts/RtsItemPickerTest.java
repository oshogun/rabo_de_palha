package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RtsItemPickerTest {
	private static final Identifier DIRT = Identifier.withDefaultNamespace("dirt");
	private static final Identifier COBBLESTONE = Identifier.withDefaultNamespace("cobblestone");
	private static final Identifier STICK = Identifier.withDefaultNamespace("stick");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Item components are bound when datapacks load; a unit test binds the minimum ItemStack needs.
		for (Item item : List.of(Items.COBBLESTONE, Items.DIRT, Items.STICK)) {
			Holder.Reference<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> ref ? ref : null;
			if (holder != null && !holder.areComponentsBound()) {
				holder.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
			}
		}
	}

	@Test
	void combineSumsAcrossWorkersInFirstSeenOrder() {
		List<ItemStack> first = List.of(ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE, 12), new ItemStack(Items.DIRT, 64));
		List<ItemStack> second = List.of(new ItemStack(Items.DIRT, 64), new ItemStack(Items.STICK, 3),
				new ItemStack(Items.COBBLESTONE, 1), ItemStack.EMPTY);
		assertEquals(List.of(new RtsItemPicker.Entry(COBBLESTONE, 13), new RtsItemPicker.Entry(DIRT, 128),
				new RtsItemPicker.Entry(STICK, 3)), RtsItemPicker.combine(List.of(first, second)));
	}

	@Test
	void combineOfEmptyInventoriesIsEmpty() {
		assertEquals(List.of(), RtsItemPicker.combine(List.of()));
		assertEquals(List.of(), RtsItemPicker.combine(List.of(List.of(ItemStack.EMPTY, ItemStack.EMPTY), List.of())));
	}

	@Test
	void entryRequiresPositiveCount() {
		assertThrows(IllegalArgumentException.class, () -> new RtsItemPicker.Entry(DIRT, 0));
	}

	@Test
	void isBlockOnlyForBlockItems() {
		assertTrue(RtsItemPicker.isBlock(DIRT));
		assertFalse(RtsItemPicker.isBlock(STICK));
		assertFalse(RtsItemPicker.isBlock(Identifier.fromNamespaceAndPath("rabodepalha", "no_such_item")));
	}

	@Test
	void entryRoundTripsCountAboveStackSize() {
		RtsItemPicker.Entry entry = new RtsItemPicker.Entry(DIRT, 300);
		assertEquals(entry, roundTrip(RtsItemPicker.Entry.STREAM_CODEC, entry));

		List<RtsItemPicker.Entry> entries = List.of(entry, new RtsItemPicker.Entry(COBBLESTONE, 3));
		assertEquals(entries, roundTrip(RtsItemPicker.Entry.STREAM_CODEC.apply(ByteBufCodecs.list(8)), entries));
	}

	private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		codec.encode(buf, value);
		T decoded = codec.decode(buf);
		assertEquals(0, buf.readableBytes(), "whole buffer consumed");
		return decoded;
	}
}
