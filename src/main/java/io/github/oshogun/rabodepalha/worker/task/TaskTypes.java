package io.github.oshogun.rabodepalha.worker.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Task types known to the worker, keyed on {@link Task#typeId()}. Only task parameters are
 * serialized; runtime state is rebuilt when the task starts. The codec works with any ops (NBT, JSON),
 * but {@link #PLACE} looks blocks up in the static block registry, so the game must be bootstrapped.
 * Each type also has a {@link TaskSummary} for the RTS panel and the queued-order markers;
 * {@link Task#describe()} stays the log text.
 */
public final class TaskTypes {
	public static final String KEY_PREFIX = "rabodepalha.task.";
	private static final Map<String, Type<?>> TYPES = new LinkedHashMap<>();
	/** Knows no blueprint, so a build summary targets only its origin. */
	public static final BlueprintSizes NO_BLUEPRINTS = id -> Optional.empty();

	/** Looks up a blueprint's size, to give a build summary its footprint. */
	@FunctionalInterface
	public interface BlueprintSizes {
		Optional<Vec3i> size(Identifier blueprint);
	}

	public static final MapCodec<MoveToTask> MOVE_TO = register(MoveToTask.TYPE_ID, MoveToTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(MoveToTask::pos),
					Codec.INT.optionalFieldOf("timeout", Approach.DEFAULT_TIMEOUT).forGetter(MoveToTask::timeoutTicks)
			).apply(instance, MoveToTask::new)),
			(task, sizes) -> new TaskSummary(KEY_PREFIX + "move_to", List.of(pos(task.pos())), List.of(task.pos())));

	public static final MapCodec<MineTask> MINE = register(MineTask.TYPE_ID, MineTask.class,
			BlockPos.CODEC.fieldOf("pos").xmap(MineTask::new, MineTask::pos),
			(task, sizes) -> summary("mine", List.of(pos(task.pos())), List.of(task.pos())));

	public static final MapCodec<UseTask> USE = register(UseTask.TYPE_ID, UseTask.class,
			BlockPos.CODEC.fieldOf("pos").xmap(UseTask::new, UseTask::pos),
			(task, sizes) -> summary("use", List.of(pos(task.pos())), List.of(task.pos())));

	/**
	 * A full block state ({@code {Name, Properties}}). Saves from before states were stored hold a bare
	 * block id, which still decodes, as the default state. An unknown id is a decode error, which drops
	 * only that queue entry on load.
	 */
	private static final Codec<BlockState> PLACE_STATE_CODEC = Codec.withAlternative(BlockState.CODEC,
			BuiltInRegistries.BLOCK.byNameCodec().xmap(Block::defaultBlockState, BlockState::getBlock));

	public static final MapCodec<PlaceTask> PLACE = register(PlaceTask.TYPE_ID, PlaceTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(PlaceTask::pos),
					PLACE_STATE_CODEC.fieldOf("block").forGetter(PlaceTask::state)
			).apply(instance, PlaceTask::new)),
			(task, sizes) -> summary("place", List.of(task.state().getBlock().getName(), pos(task.pos())),
					List.of(task.pos())));

	public static final MapCodec<DepositTask> DEPOSIT = register(DepositTask.TYPE_ID, DepositTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(DepositTask::pos),
					Identifier.CODEC.optionalFieldOf("item").forGetter(DepositTask::itemId)
			).apply(instance, DepositTask::new)),
			(task, sizes) -> task.itemId()
					.map(id -> summary("deposit_item", List.of(itemName(id), pos(task.pos())), List.of(task.pos())))
					.orElseGet(() -> summary("deposit", List.of(pos(task.pos())), List.of(task.pos()))));

	public static final MapCodec<WithdrawTask> WITHDRAW = register(WithdrawTask.TYPE_ID, WithdrawTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(WithdrawTask::pos),
					Identifier.CODEC.fieldOf("item").forGetter(WithdrawTask::itemId),
					Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(WithdrawTask::count)
			).apply(instance, WithdrawTask::new)),
			(task, sizes) -> summary("withdraw", List.of(Component.literal(String.valueOf(task.count())),
					itemName(task.itemId()), pos(task.pos())), List.of(task.pos())));

	/** Only the corners: progress is not saved, a loaded task scans the area again. */
	public static final MapCodec<DigAreaTask> DIG_AREA = register(DigAreaTask.TYPE_ID, DigAreaTask.class,
			RecordCodecBuilder.<DigAreaTask>mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("from").forGetter(DigAreaTask::from),
					BlockPos.CODEC.fieldOf("to").forGetter(DigAreaTask::to)
			).apply(instance, DigAreaTask::new)).validate(task -> task.volume() <= DigAreaTask.MAX_VOLUME
					? DataResult.success(task)
					: DataResult.error(() -> "Dig area of " + task.volume() + " blocks exceeds " + DigAreaTask.MAX_VOLUME)),
			(task, sizes) -> new TaskSummary(KEY_PREFIX + "dig_area", List.of(pos(task.from()), pos(task.to())),
					List.of(task.from(), task.to())));

	/** An item by id; an unknown id is a decode error. */
	private static final Codec<Item> ITEM_CODEC = Identifier.CODEC.comapFlatMap(
			id -> BuiltInRegistries.ITEM.getOptional(id).map(DataResult::success)
					.orElseGet(() -> DataResult.error(() -> "Unknown item: " + id)),
			BuiltInRegistries.ITEM::getKey);

	/** Only the corners and the seed: progress is not saved, a loaded task scans the field again. */
	public static final MapCodec<PlantFieldTask> PLANT_FIELD = register(PlantFieldTask.TYPE_ID, PlantFieldTask.class,
			RecordCodecBuilder.<PlantFieldTask>mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("from").forGetter(PlantFieldTask::from),
					BlockPos.CODEC.fieldOf("to").forGetter(PlantFieldTask::to),
					ITEM_CODEC.fieldOf("seed").forGetter(PlantFieldTask::seed)
			).apply(instance, PlantFieldTask::new)).validate(TaskTypes::validatePlantField),
			(task, sizes) -> new TaskSummary(KEY_PREFIX + "plant_field", List.of(
					itemName(BuiltInRegistries.ITEM.getKey(task.seed())), pos(task.from()), pos(task.to())),
					List.of(task.from(), task.to())));

	/** Only the corners: progress is not saved, a loaded task scans the field again. */
	public static final MapCodec<HarvestAreaTask> HARVEST_AREA = register(HarvestAreaTask.TYPE_ID, HarvestAreaTask.class,
			RecordCodecBuilder.<HarvestAreaTask>mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("from").forGetter(HarvestAreaTask::from),
					BlockPos.CODEC.fieldOf("to").forGetter(HarvestAreaTask::to)
			).apply(instance, HarvestAreaTask::new)).validate(TaskTypes::validateHarvestArea),
			(task, sizes) -> new TaskSummary(KEY_PREFIX + "harvest_area", List.of(pos(task.from()), pos(task.to())),
					List.of(task.from(), task.to())));

	/**
	 * Only the parameters: progress is not saved, a loaded task plans the build again. The summary targets
	 * the origin, then the footprint's min and max corners when the blueprint's size is known.
	 */
	public static final MapCodec<BuildTask> BUILD = register(BuildTask.TYPE_ID, BuildTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("origin").forGetter(BuildTask::origin),
					Identifier.CODEC.fieldOf("blueprint").forGetter(BuildTask::blueprint),
					Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(BuildTask::rotation)
			).apply(instance, BuildTask::new)),
			(task, sizes) -> new TaskSummary(KEY_PREFIX + "build", List.of(Component.literal(task.blueprint().toString()),
					pos(task.origin()), Component.literal(task.rotation().getSerializedName())), buildTargets(task, sizes)));

	/** Dispatches on a {@code type} field; an unknown type decodes to a {@link DataResult} error. */
	public static final Codec<Task<WorkerEntity>> CODEC = Codec.STRING.partialDispatch("type",
			task -> DataResult.success(task.typeId()), TaskTypes::codecFor);

	private TaskTypes() {
	}

	/** Forces class initialization so all types are registered. */
	public static void bootstrap() {
	}

	public static DataResult<MapCodec<? extends Task<WorkerEntity>>> codecFor(String typeId) {
		Type<?> type = TYPES.get(typeId);
		return type != null ? DataResult.success(type.codec()) : DataResult.error(() -> "Unknown task type: " + typeId);
	}

	/** {@link #summarize(Task, BlueprintSizes)} without blueprint sizes, so a build targets only its origin. */
	public static TaskSummary summarize(Task<WorkerEntity> task) {
		return summarize(task, NO_BLUEPRINTS);
	}

	/**
	 * A translatable description of the task and the positions it targets; an unregistered type gets
	 * {@code rabodepalha.task.unknown}. {@code sizes} gives a build its footprint.
	 */
	public static TaskSummary summarize(Task<WorkerEntity> task, BlueprintSizes sizes) {
		Type<?> type = TYPES.get(task.typeId());
		if (type == null || !type.taskClass().isInstance(task)) {
			return summary("unknown", List.of(Component.literal(task.typeId())), List.of());
		}
		return type.summarize(task, sizes);
	}

	/** The origin, then the footprint's min and max corners if the blueprint is known. The origin stays first. */
	private static List<BlockPos> buildTargets(BuildTask task, BlueprintSizes sizes) {
		Optional<Vec3i> size = sizes.size(task.blueprint());
		if (size.isEmpty()) {
			return List.of(task.origin());
		}
		BoundingBox box = BlueprintFootprint.box(task.origin(), size.get(), task.rotation());
		return List.of(task.origin(), new BlockPos(box.minX(), box.minY(), box.minZ()),
				new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
	}

	private static DataResult<PlantFieldTask> validatePlantField(PlantFieldTask task) {
		Optional<String> fieldError = validateField(task.from(), task.to());
		if (fieldError.isPresent()) {
			return DataResult.error(fieldError::get);
		}
		if (!FieldBlocks.isSeed(task.seed())) {
			return DataResult.error(() -> BuiltInRegistries.ITEM.getKey(task.seed()) + " is not a seed");
		}
		return DataResult.success(task);
	}

	private static DataResult<HarvestAreaTask> validateHarvestArea(HarvestAreaTask task) {
		return validateField(task.from(), task.to())
				.<DataResult<HarvestAreaTask>>map(error -> DataResult.error(() -> error))
				.orElseGet(() -> DataResult.success(task));
	}

	/** The error for a field over the {@link RtsField} cap, if it is. */
	private static Optional<String> validateField(BlockPos from, BlockPos to) {
		RtsField field = RtsField.of(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		if (field.exceeds()) {
			return Optional.of("Field of " + field.columns() + " columns and rise " + field.rise()
					+ " exceeds " + RtsField.MAX_COLUMNS + " columns or rise " + RtsField.MAX_RISE);
		}
		return Optional.empty();
	}

	private static <T extends Task<WorkerEntity>> MapCodec<T> register(String typeId, Class<T> taskClass, MapCodec<T> codec,
			BiFunction<T, BlueprintSizes, TaskSummary> summary) {
		if (TYPES.putIfAbsent(typeId, new Type<>(taskClass, codec, summary)) != null) {
			throw new IllegalStateException("Duplicate task type " + typeId);
		}
		return codec;
	}

	private static TaskSummary summary(String key, List<Component> args, List<BlockPos> targets) {
		return new TaskSummary(KEY_PREFIX + key, args, targets);
	}

	private static Component pos(BlockPos pos) {
		return Component.literal(pos.toShortString());
	}

	/** The item's translatable name, or the raw id when it is unknown or air. */
	private static Component itemName(Identifier id) {
		Item item = BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;
		return item == Items.AIR ? Component.literal(id.toString()) : Component.translatable(item.getDescriptionId());
	}

	private record Type<T extends Task<WorkerEntity>>(Class<T> taskClass, MapCodec<T> codec,
			BiFunction<T, BlueprintSizes, TaskSummary> summary) {
		TaskSummary summarize(Task<WorkerEntity> task, BlueprintSizes sizes) {
			return summary.apply(taskClass.cast(task), sizes);
		}
	}
}
