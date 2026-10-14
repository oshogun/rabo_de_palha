package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertUntouched;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.layFloor;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsBuildPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.BlueprintCatalog;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.BuildTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server side of the RTS build order and the blueprint list and info requests: {@link RtsOrders#handleBuild},
 * {@link RtsOrders#handleBlueprints} and {@link RtsOrders#handleBlueprintInfo} with mock players, reusing the
 * {@link RtsOrderGameTest} helpers. Blueprints are registered with {@link WorkerTaskGameTest#blueprint}.
 * Messages are checked by their translation keys.
 */
public class RtsBuildGameTest {
	private static final BlockPos WORKER_A = new BlockPos(1, 1, 6);
	private static final BlockPos WORKER_B = new BlockPos(6, 1, 1);
	private static final BlockPos ORIGIN = new BlockPos(3, 1, 3);
	/** A 2×1×3 blueprint: two cobblestone and one plank. */
	private static final Map<BlockPos, BlockState> BLOCKS = Map.of(
			new BlockPos(0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
			new BlockPos(1, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
			new BlockPos(0, 0, 2), Blocks.OAK_PLANKS.defaultBlockState());

	private static RtsBuildPayload build(BlockPos origin, Identifier blueprint, Rotation rotation, boolean append,
			WorkerEntity... workers) {
		List<Integer> ids = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			ids.add(worker.getId());
		}
		return new RtsBuildPayload(ids, origin, blueprint, rotation, append);
	}

	private static boolean isBuild(Task<WorkerEntity> task, BlockPos origin, Identifier blueprint, Rotation rotation) {
		return task instanceof BuildTask b && b.origin().equals(origin) && b.blueprint().equals(blueprint)
				&& b.rotation() == rotation;
	}

	@GameTest
	public void buildQueuesBuildTaskOnEachWorker(GameTestHelper helper) {
		layFloor(helper);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_rotated", BLOCKS);
		WorkerEntity a = helper.spawn(ModEntities.WORKER, WORKER_A);
		WorkerEntity b = helper.spawn(ModEntities.WORKER, WORKER_B);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos origin = helper.absolutePos(ORIGIN);

		RtsOrders.Result result = RtsOrders.handleBuild(player, build(origin, id, Rotation.CLOCKWISE_90, false, a, b),
				LEVEL_2, RtsOrders.MAX_TARGET_DISTANCE, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, true, 2, "build_replaced");
		List<Task<WorkerEntity>> queueA = a.tasks().snapshot();
		List<Task<WorkerEntity>> queueB = b.tasks().snapshot();
		helper.assertTrue(queueA.size() == 1 && isBuild(queueA.get(0), origin, id, Rotation.CLOCKWISE_90),
				"expected only a build at " + origin + " turned 90°, got " + queueA);
		helper.assertTrue(queueB.size() == 1 && isBuild(queueB.get(0), origin, id, Rotation.CLOCKWISE_90),
				"expected only a build at " + origin + " turned 90°, got " + queueB);
		helper.assertTrue(queueA.get(0) != queueB.get(0), "workers share one task instance");
		helper.assertTrue(a.commanders().contains(player.getUUID()), "player not recorded as commander");
		finish(helper, player);
	}

	@GameTest
	public void buildAppendKeepsQueue(GameTestHelper helper) {
		layFloor(helper);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_append", BLOCKS);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		MoveToTask first = new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 6)));
		worker.tasks().enqueue(first);
		BlockPos origin = helper.absolutePos(ORIGIN);

		RtsOrders.Result appended = RtsOrders.handleBuild(player,
				build(origin, id, Rotation.CLOCKWISE_180, true, worker), LEVEL_2, RtsOrders.MAX_TARGET_DISTANCE,
				RtsOrders.MAX_WORKER_DISTANCE);
		assertResult(helper, appended, true, 1, "build_queued");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 2 && queue.get(0) == first
				&& isBuild(queue.get(1), origin, id, Rotation.CLOCKWISE_180), "expected the build appended, got " + queue);

		RtsOrders.Result replaced = RtsOrders.handleBuild(player,
				build(origin, id, Rotation.COUNTERCLOCKWISE_90, false, worker), LEVEL_2, RtsOrders.MAX_TARGET_DISTANCE,
				RtsOrders.MAX_WORKER_DISTANCE);
		assertResult(helper, replaced, true, 1, "build_replaced");
		List<Task<WorkerEntity>> after = worker.tasks().snapshot();
		helper.assertTrue(after.size() == 1 && isBuild(after.get(0), origin, id, Rotation.COUNTERCLOCKWISE_90),
				"expected only the build, got " + after);
		finish(helper, player);
	}

	@GameTest
	public void buildUnknownBlueprintRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "gametest/no_such_rts_blueprint");

		RtsOrders.Result result = RtsOrders.handleBuild(player,
				build(helper.absolutePos(ORIGIN), id, Rotation.NONE, false, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "unknown_blueprint");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	/** Uses the overload with explicit limits, so the footprint stays inside the loaded test area. */
	@GameTest
	public void buildTooFarRejected(GameTestHelper helper) {
		layFloor(helper);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_too_far", BLOCKS);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleBuild(player,
				build(helper.absolutePos(ORIGIN), id, Rotation.CLOCKWISE_90, false, worker), LEVEL_2, 1.0,
				RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "target_too_far");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void buildNotLoadedRejected(GameTestHelper helper) {
		layFloor(helper);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_not_loaded", BLOCKS);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos origin = helper.absolutePos(ORIGIN).offset(100_000, 0, 100_000);

		RtsOrders.Result result = RtsOrders.handleBuild(player, build(origin, id, Rotation.NONE, false, worker),
				LEVEL_2, 1_000_000.0, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "not_loaded");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void buildOutOfWorldRejected(GameTestHelper helper) {
		layFloor(helper);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_out_of_world", BLOCKS);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos base = helper.absolutePos(ORIGIN);
		BlockPos origin = new BlockPos(base.getX(), helper.getLevel().getMaxY() + 1, base.getZ());

		RtsOrders.Result result = RtsOrders.handleBuild(player, build(origin, id, Rotation.NONE, false, worker),
				LEVEL_2, 1_000_000.0, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "area_out_of_world");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void blueprintListIncludesKnownBlueprint(GameTestHelper helper) {
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_listed", BLOCKS);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.BlueprintsResult result = RtsOrders.handleBlueprints(player, new RtsBlueprintsRequestPayload(false),
				LEVEL_2);

		helper.assertTrue(result.result().ok(), "list request rejected: " + result.result());
		List<Identifier> ids = result.listing().entries().stream().map(BlueprintCatalog.Entry::id).toList();
		helper.assertTrue(ids.contains(id), "listing lacks " + id + ": " + ids);
		helper.assertTrue(result.listing().entries().stream()
				.noneMatch(entry -> entry.source() == BlueprintCatalog.Source.VANILLA),
				"vanilla blueprints listed without the toggle");
		finish(helper, player);
	}

	@GameTest
	public void blueprintInfoGivesSizeAndMaterials(GameTestHelper helper) {
		Identifier id = WorkerTaskGameTest.blueprint(helper, "rts_build_info", BLOCKS);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.BlueprintInfoResult result = RtsOrders.handleBlueprintInfo(player,
				new RtsBlueprintInfoRequestPayload(id), LEVEL_2);

		helper.assertTrue(result.result().ok(), "info request rejected: " + result.result());
		helper.assertTrue(result.info().isPresent(), "no info for " + id);
		RtsBlueprintInfoPayload info = result.info().get();
		helper.assertTrue(info.id().equals(id), "info for the wrong id: " + info.id());
		helper.assertTrue(info.size().equals(new Vec3i(2, 1, 3)), "unexpected size " + info.size());
		List<RtsBlueprintInfoPayload.Material> expected = List.of(
				new RtsBlueprintInfoPayload.Material(Items.COBBLESTONE, 2),
				new RtsBlueprintInfoPayload.Material(Items.OAK_PLANKS, 1));
		helper.assertTrue(info.materials().equals(expected), "unexpected materials " + info.materials());
		finish(helper, player);
	}

	@GameTest
	public void blueprintInfoUnknownRejected(GameTestHelper helper) {
		ServerPlayer player = rtsPlayer(helper);
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "gametest/no_such_rts_info_blueprint");

		RtsOrders.BlueprintInfoResult result = RtsOrders.handleBlueprintInfo(player,
				new RtsBlueprintInfoRequestPayload(id), LEVEL_2);

		assertResult(helper, result.result(), false, 0, "unknown_blueprint");
		helper.assertTrue(result.info().isEmpty(), "info sent for an unknown blueprint: " + result.info());
		finish(helper, player);
	}
}
