package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TaskSummaryTest {
	private static final BlockPos POS = new BlockPos(1, -2, 3);
	private static final BlockPos OTHER = new BlockPos(4, 5, 6);
	private static final Pattern PLACEHOLDER = Pattern.compile("%s");

	private static JsonObject lang;

	@BeforeAll
	static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try (InputStream in = TaskSummaryTest.class.getResourceAsStream("/assets/rabodepalha/lang/en_us.json")) {
			assertNotNull(in, "en_us.json on the classpath");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	void moveTo() {
		TaskSummary summary = TaskTypes.summarize(new MoveToTask(POS, 100));
		assertSummary(summary, "rabodepalha.task.move_to", List.of(literal(POS)), List.of(POS));
	}

	@Test
	void mine() {
		assertSummary(TaskTypes.summarize(new MineTask(POS)), "rabodepalha.task.mine", List.of(literal(POS)), List.of(POS));
	}

	@Test
	void placeUsesTranslatableBlockName() {
		TaskSummary summary = TaskTypes.summarize(new PlaceTask(POS, Blocks.OAK_PLANKS.defaultBlockState()));
		assertSummary(summary, "rabodepalha.task.place", List.of(Blocks.OAK_PLANKS.getName(), literal(POS)), List.of(POS));
	}

	@Test
	void depositWithoutItem() {
		TaskSummary summary = TaskTypes.summarize(new DepositTask(POS, Optional.empty()));
		assertSummary(summary, "rabodepalha.task.deposit", List.of(literal(POS)), List.of(POS));
	}

	@Test
	void depositWithItem() {
		TaskSummary summary = TaskTypes.summarize(new DepositTask(POS, Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))));
		assertSummary(summary, "rabodepalha.task.deposit_item",
				List.of(Component.translatable(Items.DIRT.getDescriptionId()), literal(POS)), List.of(POS));
	}

	@Test
	void withdrawUsesTranslatableItemName() {
		TaskSummary summary = TaskTypes.summarize(new WithdrawTask(POS, BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 5));
		assertSummary(summary, "rabodepalha.task.withdraw",
				List.of(Component.literal("5"), Component.translatable(Items.COBBLESTONE.getDescriptionId()), literal(POS)), List.of(POS));
	}

	@Test
	void unknownItemFallsBackToId() {
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "no_such_item");
		TaskSummary summary = TaskTypes.summarize(new WithdrawTask(POS, id, 1));
		assertEquals(Component.literal(id.toString()), summary.args().get(1));
	}

	@Test
	void digAreaTargetsBothCorners() {
		TaskSummary summary = TaskTypes.summarize(new DigAreaTask(POS, OTHER));
		assertSummary(summary, "rabodepalha.task.dig_area", List.of(literal(POS), literal(OTHER)), List.of(POS, OTHER));
	}

	@Test
	void build() {
		Identifier blueprint = Identifier.fromNamespaceAndPath("rabodepalha", "hut");
		TaskSummary summary = TaskTypes.summarize(new BuildTask(POS, blueprint, Rotation.CLOCKWISE_90));
		assertSummary(summary, "rabodepalha.task.build", List.of(Component.literal("rabodepalha:hut"), literal(POS),
				Component.literal(Rotation.CLOCKWISE_90.getSerializedName())), List.of(POS));
	}

	@Test
	void buildWithKnownBlueprintTargetsOriginAndFootprint() {
		Identifier blueprint = Identifier.fromNamespaceAndPath("rabodepalha", "hut");
		Vec3i size = new Vec3i(2, 3, 4);
		TaskTypes.BlueprintSizes sizes = id -> id.equals(blueprint) ? Optional.of(size) : Optional.empty();
		for (Rotation rotation : Rotation.values()) {
			TaskSummary summary = TaskTypes.summarize(new BuildTask(POS, blueprint, rotation), sizes);
			BoundingBox box = BlueprintFootprint.box(POS, size, rotation);
			assertEquals(List.of(POS, new BlockPos(box.minX(), box.minY(), box.minZ()),
					new BlockPos(box.maxX(), box.maxY(), box.maxZ())), summary.targets(), "rotation " + rotation);
		}
		// Turned 90° clockwise, a 2 wide, 4 deep template extends 3 blocks west of the origin.
		TaskSummary turned = TaskTypes.summarize(new BuildTask(POS, blueprint, Rotation.CLOCKWISE_90), sizes);
		assertEquals(List.of(POS, POS.offset(-3, 0, 0), POS.offset(0, 2, 1)), turned.targets());
	}

	@Test
	void buildWithUnknownBlueprintTargetsOrigin() {
		Identifier blueprint = Identifier.fromNamespaceAndPath("rabodepalha", "hut");
		TaskSummary summary = TaskTypes.summarize(new BuildTask(POS, blueprint, Rotation.NONE), id -> Optional.empty());
		assertEquals(List.of(POS), summary.targets());
	}

	@Test
	void unknownTypeFallsBack() {
		TaskSummary summary = TaskTypes.summarize(new UnknownTask());
		assertSummary(summary, "rabodepalha.task.unknown", List.of(Component.literal("no_such_type")), List.of());
	}

	@Test
	void langHasWorkerKeys() {
		assertTrue(lang.has("entity.rabodepalha.worker"));
		assertEquals(2, placeholders("rabodepalha.worker.name"));
	}

	@Test
	void streamCodecRoundTrips() {
		TaskSummary summary = new TaskSummary("rabodepalha.task.place",
				List.of(Blocks.OAK_PLANKS.getName(), literal(POS)), List.of(POS, OTHER, BlockPos.ZERO));
		assertEquals(summary, roundTrip(summary));
	}

	@Test
	void capsTruncate() {
		List<Component> args = List.of(Component.literal("a"), Component.literal("b"), Component.literal("c"),
				Component.literal("d"), Component.literal("e"));
		BlockPos fourth = new BlockPos(7, 8, 9);
		TaskSummary summary = new TaskSummary("rabodepalha.task.unknown", args, List.of(POS, OTHER, BlockPos.ZERO, fourth));
		assertEquals(args.subList(0, TaskSummary.MAX_ARGS), summary.args());
		assertEquals(3, TaskSummary.MAX_TARGETS);
		assertEquals(List.of(POS, OTHER, BlockPos.ZERO), summary.targets());
		assertEquals(summary, roundTrip(summary));
	}

	private static void assertSummary(TaskSummary summary, String key, List<Component> args, List<BlockPos> targets) {
		assertEquals(key, summary.key());
		assertEquals(args, summary.args());
		assertEquals(targets, summary.targets());
		assertTrue(lang.has(key), "lang key " + key);
		assertEquals(args.size(), placeholders(key), "placeholders in " + key);
	}

	private static int placeholders(String key) {
		Matcher matcher = PLACEHOLDER.matcher(lang.get(key).getAsString());
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static Component literal(BlockPos pos) {
		return Component.literal(pos.toShortString());
	}

	private static TaskSummary roundTrip(TaskSummary summary) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		TaskSummary.STREAM_CODEC.encode(buf, summary);
		return TaskSummary.STREAM_CODEC.decode(buf);
	}

	private static final class UnknownTask implements Task<WorkerEntity> {
		@Override
		public void start(WorkerEntity worker) {
		}

		@Override
		public TaskOutcome tick(WorkerEntity worker) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void stop(WorkerEntity worker, boolean cancelled) {
		}

		@Override
		public String describe() {
			return "unknown";
		}

		@Override
		public String typeId() {
			return "no_such_type";
		}
	}
}
