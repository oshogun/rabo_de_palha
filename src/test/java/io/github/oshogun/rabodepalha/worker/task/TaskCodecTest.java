package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TaskCodecTest {
	@BeforeAll
	static void bootstrap() {
		// PlaceTask's codec reads the block registry.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void moveToRoundTrips() {
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			MoveToTask task = assertInstanceOf(MoveToTask.class, roundTrip(ops, new MoveToTask(new BlockPos(1, -2, 3), 250)));
			assertEquals(new BlockPos(1, -2, 3), task.pos());
			assertEquals(250, task.timeoutTicks());
		}
	}

	@Test
	void moveToTimeoutDefaults() {
		JsonObject json = new JsonObject();
		json.addProperty("type", MoveToTask.TYPE_ID);
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());

		MoveToTask task = assertInstanceOf(MoveToTask.class, TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		assertEquals(Approach.DEFAULT_TIMEOUT, task.timeoutTicks());
	}

	@Test
	void mineRoundTrips() {
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			MineTask task = assertInstanceOf(MineTask.class, roundTrip(ops, new MineTask(new BlockPos(-7, 64, 12))));
			assertEquals(new BlockPos(-7, 64, 12), task.pos());
		}
	}

	@Test
	void useRoundTrips() {
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			UseTask task = assertInstanceOf(UseTask.class, roundTrip(ops, new UseTask(new BlockPos(4, 70, -3))));
			assertEquals(new BlockPos(4, 70, -3), task.pos());
		}
	}

	@Test
	void placeRoundTrips() {
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(StairBlock.FACING, Direction.EAST)
				.setValue(StairBlock.HALF, Half.TOP);
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			PlaceTask task = assertInstanceOf(PlaceTask.class, roundTrip(ops, new PlaceTask(new BlockPos(0, 5, 0), stairs)));
			assertEquals(new BlockPos(0, 5, 0), task.pos());
			assertEquals(stairs, task.state());
		}
	}

	@Test
	void placeLegacyIdDecodes() {
		JsonObject json = new JsonObject();
		json.addProperty("type", PlaceTask.TYPE_ID);
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.addProperty("block", "minecraft:oak_stairs");

		PlaceTask task = assertInstanceOf(PlaceTask.class, TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		assertEquals(Blocks.OAK_STAIRS.defaultBlockState(), task.state());
	}

	@Test
	void placeLegacyUnknownIdIsAnError() {
		JsonObject json = new JsonObject();
		json.addProperty("type", PlaceTask.TYPE_ID);
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.addProperty("block", "minecraft:no_such_block");

		// Dropped on load like any other undecodable entry; see undecodableEntriesAreSkippedPerElement.
		assertTrue(TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).isError());
	}

	@Test
	void depositWithItemRoundTrips() {
		Identifier dirt = Identifier.withDefaultNamespace("dirt");
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			DepositTask task = assertInstanceOf(DepositTask.class,
					roundTrip(ops, new DepositTask(new BlockPos(2, 3, 4), Optional.of(dirt))));
			assertEquals(new BlockPos(2, 3, 4), task.pos());
			assertEquals(Optional.of(dirt), task.itemId());
		}
	}

	@Test
	void depositWithoutItemRoundTrips() {
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			DepositTask task = assertInstanceOf(DepositTask.class,
					roundTrip(ops, new DepositTask(new BlockPos(-1, 0, 1), Optional.empty())));
			assertEquals(new BlockPos(-1, 0, 1), task.pos());
			assertEquals(Optional.empty(), task.itemId());
		}
		JsonObject json = encode(new DepositTask(BlockPos.ZERO, Optional.empty())).getAsJsonObject();
		assertFalse(json.has("item"), "absent item was written: " + json);
	}

	@Test
	void withdrawRoundTrips() {
		Identifier cobblestone = Identifier.withDefaultNamespace("cobblestone");
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			WithdrawTask task = assertInstanceOf(WithdrawTask.class,
					roundTrip(ops, new WithdrawTask(new BlockPos(7, 8, 9), cobblestone, 32)));
			assertEquals(new BlockPos(7, 8, 9), task.pos());
			assertEquals(cobblestone, task.itemId());
			assertEquals(32, task.count());
		}
	}

	@Test
	void withdrawCountDefaultsToOne() {
		JsonObject json = withdrawJson();

		WithdrawTask task = assertInstanceOf(WithdrawTask.class, TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		assertEquals(1, task.count());
	}

	@Test
	void withdrawCountZeroIsAnError() {
		JsonObject json = withdrawJson();
		json.addProperty("count", 0);

		assertTrue(TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).isError());
	}

	@Test
	void digAreaRoundTrips() {
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			// Corners keep their order, even when "from" is the upper one.
			DigAreaTask task = assertInstanceOf(DigAreaTask.class,
					roundTrip(ops, new DigAreaTask(new BlockPos(4, 70, -2), new BlockPos(1, 68, -5))));
			assertEquals(new BlockPos(4, 70, -2), task.from());
			assertEquals(new BlockPos(1, 68, -5), task.to());
		}
	}

	@Test
	void digAreaTooLargeIsAnError() {
		JsonObject json = new JsonObject();
		json.addProperty("type", DigAreaTask.TYPE_ID);
		json.add("from", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.add("to", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, new BlockPos(16, 16, 16)).getOrThrow());

		assertTrue(TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).isError());
	}

	@Test
	void buildRoundTrips() {
		Identifier hut = Identifier.fromNamespaceAndPath("rabodepalha", "hut");
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			BuildTask task = assertInstanceOf(BuildTask.class,
					roundTrip(ops, new BuildTask(new BlockPos(3, 64, -8), hut, Rotation.CLOCKWISE_90)));
			assertEquals(new BlockPos(3, 64, -8), task.origin());
			assertEquals(hut, task.blueprint());
			assertEquals(Rotation.CLOCKWISE_90, task.rotation());
		}
	}

	@Test
	void buildDefaultsRotationNone() {
		JsonObject json = new JsonObject();
		json.addProperty("type", BuildTask.TYPE_ID);
		json.add("origin", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.addProperty("blueprint", "rabodepalha:hut");

		BuildTask task = assertInstanceOf(BuildTask.class, TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		assertEquals(Rotation.NONE, task.rotation());
	}

	private static JsonObject withdrawJson() {
		JsonObject json = new JsonObject();
		json.addProperty("type", WithdrawTask.TYPE_ID);
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, BlockPos.ZERO).getOrThrow());
		json.addProperty("item", "minecraft:dirt");
		return json;
	}

	@Test
	void unknownTypeIsAnError() {
		JsonObject json = new JsonObject();
		json.addProperty("type", "bogus");

		DataResult<Task<WorkerEntity>> result = TaskTypes.CODEC.parse(JsonOps.INSTANCE, json);

		assertTrue(result.isError());
		assertTrue(result.error().orElseThrow().message().contains("bogus"));
	}

	@Test
	void undecodableEntriesAreSkippedPerElement() {
		JsonArray list = new JsonArray();
		list.add(encode(new MineTask(new BlockPos(1, 2, 3))));
		JsonObject bogus = new JsonObject();
		bogus.addProperty("type", "bogus");
		list.add(bogus);
		list.add(encode(new MoveToTask(new BlockPos(4, 5, 6), 99)));
		list.add(encode(new DigAreaTask(new BlockPos(0, 1, 0), new BlockPos(2, 3, 2))));

		// Same per-element decoding as TagValueInput's typed list: failures are skipped, the rest survive.
		List<Task<WorkerEntity>> decoded = new ArrayList<>();
		for (JsonElement element : list) {
			TaskTypes.CODEC.parse(JsonOps.INSTANCE, element).result().ifPresent(decoded::add);
		}

		assertEquals(3, decoded.size());
		assertEquals(new BlockPos(1, 2, 3), assertInstanceOf(MineTask.class, decoded.get(0)).pos());
		assertEquals(new BlockPos(4, 5, 6), assertInstanceOf(MoveToTask.class, decoded.get(1)).pos());
		assertEquals(new BlockPos(2, 3, 2), assertInstanceOf(DigAreaTask.class, decoded.get(2)).to());
	}

	private static JsonElement encode(Task<WorkerEntity> task) {
		return TaskTypes.CODEC.encodeStart(JsonOps.INSTANCE, task).getOrThrow();
	}

	private static <T> Task<WorkerEntity> roundTrip(DynamicOps<T> ops, Task<WorkerEntity> task) {
		T encoded = TaskTypes.CODEC.encodeStart(ops, task).getOrThrow();
		return TaskTypes.CODEC.parse(ops, encoded).getOrThrow();
	}
}
