package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

class TaskCodecTest {
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
	void placeRoundTrips() {
		Identifier dirt = Identifier.withDefaultNamespace("dirt");
		for (DynamicOps<?> ops : List.of(JsonOps.INSTANCE, NbtOps.INSTANCE)) {
			PlaceTask task = assertInstanceOf(PlaceTask.class, roundTrip(ops, new PlaceTask(new BlockPos(0, 5, 0), dirt)));
			assertEquals(new BlockPos(0, 5, 0), task.pos());
			assertEquals(dirt, task.blockId());
		}
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

		// Same per-element decoding as TagValueInput's typed list: failures are skipped, the rest survive.
		List<Task<WorkerEntity>> decoded = new ArrayList<>();
		for (JsonElement element : list) {
			TaskTypes.CODEC.parse(JsonOps.INSTANCE, element).result().ifPresent(decoded::add);
		}

		assertEquals(2, decoded.size());
		assertEquals(new BlockPos(1, 2, 3), assertInstanceOf(MineTask.class, decoded.get(0)).pos());
		assertEquals(new BlockPos(4, 5, 6), assertInstanceOf(MoveToTask.class, decoded.get(1)).pos());
	}

	private static JsonElement encode(Task<WorkerEntity> task) {
		return TaskTypes.CODEC.encodeStart(JsonOps.INSTANCE, task).getOrThrow();
	}

	private static <T> Task<WorkerEntity> roundTrip(DynamicOps<T> ops, Task<WorkerEntity> task) {
		T encoded = TaskTypes.CODEC.encodeStart(ops, task).getOrThrow();
		return TaskTypes.CODEC.parse(ops, encoded).getOrThrow();
	}
}
