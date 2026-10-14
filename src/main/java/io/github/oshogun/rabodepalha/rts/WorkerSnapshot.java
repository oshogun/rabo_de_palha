package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import io.github.oshogun.rabodepalha.task.TaskRunner;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.TaskSummary;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ByIdMap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * What the RTS panel shows about one watched worker: its name, current and queued tasks, inventory and
 * chunk-ticket status. The name and task arguments are Components so the client translates them.
 */
public record WorkerSnapshot(int entityId, Component name, Optional<TaskSummary> current, List<QueuedTask> queued,
		int queuedTotal, List<ItemStack> inventory, ChunkStatus chunks) {
	/** Queued tasks sent per worker; {@link #queuedTotal} still reports the full length. */
	public static final int MAX_QUEUED = 16;
	public static final int MAX_SLOTS = WorkerEntity.INVENTORY_SIZE;

	public static final StreamCodec<RegistryFriendlyByteBuf, WorkerSnapshot> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, WorkerSnapshot::entityId,
			ComponentSerialization.STREAM_CODEC, WorkerSnapshot::name,
			ByteBufCodecs.optional(TaskSummary.STREAM_CODEC), WorkerSnapshot::current,
			QueuedTask.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_QUEUED)), WorkerSnapshot::queued,
			ByteBufCodecs.VAR_INT, WorkerSnapshot::queuedTotal,
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SLOTS)), WorkerSnapshot::inventory,
			ChunkStatus.STREAM_CODEC, WorkerSnapshot::chunks,
			WorkerSnapshot::new);

	public WorkerSnapshot {
		queued = List.copyOf(queued.size() > MAX_QUEUED ? queued.subList(0, MAX_QUEUED) : queued);
		queuedTotal = Math.max(queuedTotal, queued.size());
		List<ItemStack> slots = inventory.size() > MAX_SLOTS ? inventory.subList(0, MAX_SLOTS) : inventory;
		inventory = slots.stream().map(ItemStack::copy).toList();
	}

	/** A queued task and the runtime id a Remove order refers to. */
	public record QueuedTask(long taskId, TaskSummary summary) {
		public static final StreamCodec<RegistryFriendlyByteBuf, QueuedTask> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_LONG, QueuedTask::taskId,
				TaskSummary.STREAM_CODEC, QueuedTask::summary,
				QueuedTask::new);
	}

	/** The chunk-ticket state {@code /rdp list} shows. */
	public enum ChunkStatus {
		LOADED,
		CAP_REACHED,
		NOT_LOADED;

		private static final IntFunction<ChunkStatus> BY_ID = ByIdMap.continuous(ChunkStatus::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
		public static final StreamCodec<ByteBuf, ChunkStatus> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, ChunkStatus::ordinal);

		public static ChunkStatus of(WorkerEntity worker) {
			if (worker.holdsChunkTicket()) {
				return LOADED;
			}
			return worker.chunkTicketRefused() ? CAP_REACHED : NOT_LOADED;
		}
	}

	/** The worker's snapshot; on the server, build summaries carry their footprint from the loaded templates. */
	public static WorkerSnapshot of(WorkerEntity worker) {
		TaskTypes.BlueprintSizes sizes = worker.level() instanceof ServerLevel level
				? id -> level.getStructureTemplateManager().get(id).map(StructureTemplate::getSize)
				: TaskTypes.NO_BLUEPRINTS;
		TaskRunner<WorkerEntity> tasks = worker.tasks();
		List<TaskRunner.Pending<WorkerEntity>> pending = tasks.pendingEntries();
		List<QueuedTask> queued = new ArrayList<>(Math.min(pending.size(), MAX_QUEUED));
		for (TaskRunner.Pending<WorkerEntity> entry : pending) {
			if (queued.size() >= MAX_QUEUED) {
				break;
			}
			queued.add(new QueuedTask(entry.id(), TaskTypes.summarize(entry.task(), sizes)));
		}
		SimpleContainer container = worker.getInventory();
		List<ItemStack> inventory = new ArrayList<>(container.getContainerSize());
		for (int i = 0; i < container.getContainerSize(); i++) {
			inventory.add(container.getItem(i));
		}
		return new WorkerSnapshot(worker.getId(), worker.label(), tasks.current().map(task -> TaskTypes.summarize(task, sizes)),
				queued, pending.size(), inventory, ChunkStatus.of(worker));
	}

	/** Value equality, with {@link ItemStack#matches} for stacks (ItemStack has identity equals). */
	public static boolean matches(WorkerSnapshot a, WorkerSnapshot b) {
		if (a.entityId != b.entityId || a.queuedTotal != b.queuedTotal || a.chunks != b.chunks
				|| !a.name.equals(b.name) || !a.current.equals(b.current) || !a.queued.equals(b.queued)
				|| a.inventory.size() != b.inventory.size()) {
			return false;
		}
		for (int i = 0; i < a.inventory.size(); i++) {
			if (!ItemStack.matches(a.inventory.get(i), b.inventory.get(i))) {
				return false;
			}
		}
		return true;
	}
}
