package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import io.github.oshogun.rabodepalha.worker.WorkerScaffold;
import io.github.oshogun.rabodepalha.worker.WorkerScaffold.ScaffoldBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScaffoldBlockCodecTest {
	@BeforeAll
	static void bootstrap() {
		// The codec reads the block registry.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void pillarBlockRoundTripsWithoutAStandSpot() {
		ScaffoldBlock block = new ScaffoldBlock(new BlockPos(3, -2, 7), Blocks.COBBLESTONE, Optional.empty());
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			assertEquals(block, roundTrip(ops, block));
		}
		JsonObject json = ScaffoldBlock.CODEC.encodeStart(JsonOps.INSTANCE, block).getOrThrow().getAsJsonObject();
		assertEquals("minecraft:cobblestone", json.get("block").getAsString());
		assertFalse(json.has("stand_at"));
	}

	@Test
	void bridgeBlockRoundTripsWithItsStandSpot() {
		ScaffoldBlock block = new ScaffoldBlock(new BlockPos(1, 64, 0), Blocks.DIRT, Optional.of(new BlockPos(0, 65, 0)));
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			assertEquals(block, roundTrip(ops, block));
		}
	}

	@Test
	void unknownBlockFailsToDecode() {
		JsonObject json = new JsonObject();
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.addProperty("block", "minecraft:no_such_block");

		assertTrue(ScaffoldBlock.CODEC.parse(JsonOps.INSTANCE, (JsonElement) json).isError());
	}

	@Test
	void cleanupOrderReversesPlacedBlocks() {
		ScaffoldBlock first = new ScaffoldBlock(new BlockPos(0, 65, 0), Blocks.COBBLESTONE, Optional.empty());
		ScaffoldBlock second = new ScaffoldBlock(new BlockPos(0, 66, 0), Blocks.DIRT, Optional.empty());
		ScaffoldBlock third = new ScaffoldBlock(new BlockPos(0, 67, 0), Blocks.COBBLESTONE, Optional.empty());

		assertEquals(List.of(third, second, first), ScaffoldPlanner.cleanupOrder(List.of(first, second, third)));
	}

	@Test
	void grownOverDirtIsStillPlaced() {
		assertTrue(WorkerScaffold.stillPlaced(Blocks.DIRT.defaultBlockState(), Blocks.DIRT));
		assertTrue(WorkerScaffold.stillPlaced(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT));
		assertTrue(WorkerScaffold.stillPlaced(Blocks.MYCELIUM.defaultBlockState(), Blocks.DIRT));
		assertTrue(WorkerScaffold.stillPlaced(Blocks.PODZOL.defaultBlockState(), Blocks.DIRT));
		assertFalse(WorkerScaffold.stillPlaced(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.COBBLESTONE));
		assertFalse(WorkerScaffold.stillPlaced(Blocks.ANDESITE.defaultBlockState(), Blocks.DIRT));
	}

	@Test
	void sanitizeKeepsAPillarWithinTheCap() {
		List<ScaffoldBlock> pillar = new ArrayList<>();
		for (int i = 0; i < ScaffoldPlanner.MAX_PILLAR + 3; i++) {
			pillar.add(new ScaffoldBlock(new BlockPos(2, 64 + i, 5), Blocks.COBBLESTONE, Optional.empty()));
		}

		assertEquals(pillar.subList(0, ScaffoldPlanner.MAX_PILLAR), WorkerScaffold.sanitize(pillar, block -> true));
	}

	@Test
	void sanitizeKeepsABridgeWithinTheCap() {
		List<ScaffoldBlock> bridge = new ArrayList<>();
		BlockPos anchor = new BlockPos(0, 65, 0);
		for (int k = 1; k <= ScaffoldPlanner.MAX_BRIDGE + 2; k++) {
			BlockPos cell = anchor.below().east(k);
			BlockPos standAt = k == 1 ? anchor : anchor.below().east(k - 1).above();
			bridge.add(new ScaffoldBlock(cell, Blocks.DIRT, Optional.of(standAt)));
		}

		assertEquals(bridge.subList(0, ScaffoldPlanner.MAX_BRIDGE), WorkerScaffold.sanitize(bridge, block -> true));
	}

	@Test
	void sanitizeStopsAtTheFirstUntrustedEntry() {
		ScaffoldBlock base = new ScaffoldBlock(new BlockPos(0, 64, 0), Blocks.COBBLESTONE, Optional.empty());
		ScaffoldBlock next = new ScaffoldBlock(new BlockPos(0, 65, 0), Blocks.COBBLESTONE, Optional.empty());
		ScaffoldBlock bedrock = new ScaffoldBlock(new BlockPos(0, 66, 0), Blocks.BEDROCK, Optional.empty());
		ScaffoldBlock detached = new ScaffoldBlock(new BlockPos(9, 30, 9), Blocks.COBBLESTONE, Optional.empty());
		ScaffoldBlock bridgeMixedIn = new ScaffoldBlock(new BlockPos(1, 65, 0), Blocks.COBBLESTONE,
				Optional.of(new BlockPos(0, 66, 0)));
		ScaffoldBlock farStand = new ScaffoldBlock(new BlockPos(5, 64, 0), Blocks.DIRT, Optional.of(new BlockPos(0, 65, 0)));
		Predicate<Block> usable = block -> block != Blocks.BEDROCK;

		assertEquals(List.of(base, next), WorkerScaffold.sanitize(List.of(base, next, bedrock, next), usable));
		assertEquals(List.of(base, next), WorkerScaffold.sanitize(List.of(base, next, detached), usable));
		assertEquals(List.of(base, next), WorkerScaffold.sanitize(List.of(base, next, bridgeMixedIn), usable));
		assertEquals(List.of(), WorkerScaffold.sanitize(List.of(farStand), usable));
		assertEquals(List.of(), WorkerScaffold.sanitize(List.of(bedrock), usable));
	}

	private static <T> ScaffoldBlock roundTrip(DynamicOps<T> ops, ScaffoldBlock block) {
		T encoded = ScaffoldBlock.CODEC.encodeStart(ops, block).getOrThrow();
		return ScaffoldBlock.CODEC.parse(ops, encoded).getOrThrow();
	}
}
