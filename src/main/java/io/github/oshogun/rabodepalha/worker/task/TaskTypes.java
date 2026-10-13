package io.github.oshogun.rabodepalha.worker.task;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * Task types known to the worker, keyed on {@link Task#typeId()}. Only task parameters are
 * serialized; runtime state is rebuilt when the task starts. No registries are involved, so the
 * codec works with any ops (NBT, JSON) without a bootstrapped game.
 */
public final class TaskTypes {
	private static final Map<String, MapCodec<? extends Task<WorkerEntity>>> TYPES = new LinkedHashMap<>();

	public static final MapCodec<MoveToTask> MOVE_TO = register(MoveToTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(MoveToTask::pos),
					Codec.INT.optionalFieldOf("timeout", Approach.DEFAULT_TIMEOUT).forGetter(MoveToTask::timeoutTicks)
			).apply(instance, MoveToTask::new)));

	public static final MapCodec<MineTask> MINE = register(MineTask.TYPE_ID,
			BlockPos.CODEC.fieldOf("pos").xmap(MineTask::new, MineTask::pos));

	public static final MapCodec<PlaceTask> PLACE = register(PlaceTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(PlaceTask::pos),
					Identifier.CODEC.fieldOf("block").forGetter(PlaceTask::blockId)
			).apply(instance, PlaceTask::new)));

	/** Dispatches on a {@code type} field; an unknown type decodes to a {@link DataResult} error. */
	public static final Codec<Task<WorkerEntity>> CODEC = Codec.STRING.partialDispatch("type",
			task -> DataResult.success(task.typeId()), TaskTypes::codecFor);

	private TaskTypes() {
	}

	/** Forces class initialization so all types are registered. */
	public static void bootstrap() {
	}

	public static DataResult<MapCodec<? extends Task<WorkerEntity>>> codecFor(String typeId) {
		MapCodec<? extends Task<WorkerEntity>> codec = TYPES.get(typeId);
		return codec != null ? DataResult.success(codec) : DataResult.error(() -> "Unknown task type: " + typeId);
	}

	private static <T extends Task<WorkerEntity>> MapCodec<T> register(String typeId, MapCodec<T> codec) {
		if (TYPES.putIfAbsent(typeId, codec) != null) {
			throw new IllegalStateException("Duplicate task type " + typeId);
		}
		return codec;
	}
}
