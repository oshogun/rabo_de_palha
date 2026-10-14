package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import io.github.oshogun.rabodepalha.network.RtsRemoveTaskPayload;
import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsWatchPayload;
import io.github.oshogun.rabodepalha.worker.task.TaskSummary;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkerSnapshotCodecTest {
	private static final BlockPos POS = new BlockPos(1, -2, 3);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Item components are bound when datapacks load; a unit test binds the minimum ItemStack needs.
		for (Item item : List.of(Items.COBBLESTONE, Items.DIRT)) {
			Holder.Reference<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> ref ? ref : null;
			if (holder != null && !holder.areComponentsBound()) {
				holder.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
			}
		}
	}

	@Test
	void roundTripsEveryChunkStatus() {
		for (WorkerSnapshot.ChunkStatus status : WorkerSnapshot.ChunkStatus.values()) {
			WorkerSnapshot snapshot = snapshot(3, status);
			WorkerSnapshot decoded = roundTrip(WorkerSnapshot.STREAM_CODEC, snapshot);
			assertSame(snapshot, decoded);
			assertEquals(status, decoded.chunks());
			assertEquals(snapshot.queued(), decoded.queued());
			assertTrue(decoded.inventory().get(1).isEmpty());
			assertTrue(ItemStack.matches(new ItemStack(Items.COBBLESTONE, 12), decoded.inventory().get(0)));
		}
	}

	@Test
	void roundTripsIdleWorker() {
		WorkerSnapshot snapshot = new WorkerSnapshot(7, name(), Optional.empty(), List.of(), 0, List.of(),
				WorkerSnapshot.ChunkStatus.NOT_LOADED);
		WorkerSnapshot decoded = roundTrip(WorkerSnapshot.STREAM_CODEC, snapshot);
		assertSame(snapshot, decoded);
		assertTrue(decoded.current().isEmpty());
	}

	@Test
	void queuedIsTruncatedButTotalKept() {
		WorkerSnapshot snapshot = snapshot(WorkerSnapshot.MAX_QUEUED + 5, WorkerSnapshot.ChunkStatus.LOADED);
		assertEquals(WorkerSnapshot.MAX_QUEUED, snapshot.queued().size());
		assertEquals(WorkerSnapshot.MAX_QUEUED + 5, snapshot.queuedTotal());
		assertEquals(WorkerSnapshot.MAX_QUEUED + 5, roundTrip(WorkerSnapshot.STREAM_CODEC, snapshot).queuedTotal());
	}

	@Test
	void inventoryIsTruncated() {
		List<ItemStack> stacks = IntStream.range(0, WorkerSnapshot.MAX_SLOTS + 3).mapToObj(i -> new ItemStack(Items.DIRT)).toList();
		WorkerSnapshot snapshot = new WorkerSnapshot(1, name(), Optional.empty(), List.of(), 0, stacks,
				WorkerSnapshot.ChunkStatus.LOADED);
		assertEquals(WorkerSnapshot.MAX_SLOTS, snapshot.inventory().size());
	}

	@Test
	void decodingTooManyQueuedFails() {
		RegistryFriendlyByteBuf buf = buffer();
		ByteBufCodecs.VAR_INT.encode(buf, 1);
		ComponentSerialization.STREAM_CODEC.encode(buf, name());
		ByteBufCodecs.BOOL.encode(buf, false);
		ByteBufCodecs.VAR_INT.encode(buf, WorkerSnapshot.MAX_QUEUED + 1);
		assertThrows(RuntimeException.class, () -> WorkerSnapshot.STREAM_CODEC.decode(buf));
	}

	@Test
	void matchesComparesStacksByValue() {
		WorkerSnapshot a = snapshot(2, WorkerSnapshot.ChunkStatus.LOADED);
		WorkerSnapshot b = snapshot(2, WorkerSnapshot.ChunkStatus.LOADED);
		assertTrue(WorkerSnapshot.matches(a, b));
		assertFalse(WorkerSnapshot.matches(a, snapshot(2, WorkerSnapshot.ChunkStatus.CAP_REACHED)));
		assertFalse(WorkerSnapshot.matches(a, snapshot(1, WorkerSnapshot.ChunkStatus.LOADED)));
		WorkerSnapshot moreCobble = new WorkerSnapshot(a.entityId(), a.name(), a.current(), a.queued(), a.queuedTotal(),
				List.of(new ItemStack(Items.COBBLESTONE, 13), ItemStack.EMPTY), a.chunks());
		assertFalse(WorkerSnapshot.matches(a, moreCobble));
	}

	@Test
	void snapshotPayloadRoundTrips() {
		RtsSnapshotPayload payload = new RtsSnapshotPayload(List.of(snapshot(2, WorkerSnapshot.ChunkStatus.LOADED)), List.of(9, 11));
		RtsSnapshotPayload decoded = roundTrip(RtsSnapshotPayload.STREAM_CODEC, payload);
		assertEquals(1, decoded.snapshots().size());
		assertSame(payload.snapshots().get(0), decoded.snapshots().get(0));
		assertEquals(List.of(9, 11), decoded.gone());
	}

	@Test
	void watchPayloadRoundTripsAndCaps() {
		RtsWatchPayload payload = new RtsWatchPayload(List.of(1, 2, 3));
		assertEquals(payload, roundTrip(RtsWatchPayload.STREAM_CODEC, payload));

		RtsWatchPayload tooMany = new RtsWatchPayload(IntStream.range(0, RtsWatchPayload.MAX_WATCHED + 1).boxed().toList());
		assertThrows(RuntimeException.class, () -> RtsWatchPayload.STREAM_CODEC.encode(buffer(), tooMany));

		RegistryFriendlyByteBuf buf = buffer();
		ByteBufCodecs.VAR_INT.encode(buf, RtsWatchPayload.MAX_WATCHED + 1);
		for (int i = 0; i <= RtsWatchPayload.MAX_WATCHED; i++) {
			ByteBufCodecs.VAR_INT.encode(buf, i);
		}
		assertThrows(RuntimeException.class, () -> RtsWatchPayload.STREAM_CODEC.decode(buf));
	}

	@Test
	void removeTaskPayloadRoundTrips() {
		RtsRemoveTaskPayload payload = new RtsRemoveTaskPayload(42, Long.MAX_VALUE);
		assertEquals(payload, roundTrip(RtsRemoveTaskPayload.STREAM_CODEC, payload));
	}

	/**
	 * Like {@link WorkerSnapshot#matches}, but compares the name by its JSON: a plain literal translation
	 * argument decodes as a String, so the decoded Component is not {@code equals} to the original.
	 */
	private static void assertSame(WorkerSnapshot expected, WorkerSnapshot actual) {
		assertEquals(json(expected.name()), json(actual.name()));
		WorkerSnapshot renamed = new WorkerSnapshot(actual.entityId(), expected.name(), actual.current(), actual.queued(),
				actual.queuedTotal(), actual.inventory(), actual.chunks());
		assertTrue(WorkerSnapshot.matches(expected, renamed));
	}

	private static JsonElement json(Component component) {
		return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).getOrThrow();
	}

	private static Component name() {
		return Component.translatable("rabodepalha.worker.name", Component.translatable("entity.rabodepalha.worker"),
				Component.literal("5"));
	}

	private static WorkerSnapshot snapshot(int queuedCount, WorkerSnapshot.ChunkStatus chunks) {
		TaskSummary current = new TaskSummary("rabodepalha.task.place",
				List.of(Blocks.OAK_PLANKS.getName(), Component.literal(POS.toShortString())), List.of());
		List<WorkerSnapshot.QueuedTask> queued = new ArrayList<>();
		for (int i = 0; i < queuedCount; i++) {
			queued.add(new WorkerSnapshot.QueuedTask(i + 1L,
					new TaskSummary("rabodepalha.task.move_to", List.of(Component.literal(POS.toShortString())), List.of(POS))));
		}
		return new WorkerSnapshot(5, name(), Optional.of(current), queued, queuedCount,
				List.of(new ItemStack(Items.COBBLESTONE, 12), ItemStack.EMPTY), chunks);
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
	}

	private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buf = buffer();
		codec.encode(buf, value);
		T decoded = codec.decode(buf);
		assertEquals(0, buf.readableBytes(), "whole buffer consumed");
		return decoded;
	}
}
