package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.worker.task.Approach;
import io.github.oshogun.rabodepalha.worker.task.ScaffoldPlanner;
import io.github.oshogun.rabodepalha.worker.task.StandSpots;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The throwaway blocks a worker placed to pillar up or bridge a gap, in placement order, and their cleanup.
 *
 * <p>While an {@link Approach} is scaffolding it {@link #claim claims} the record and adds every block it
 * places. Once it {@link #release releases} it (the task ended, failed, was cancelled or suspended), the
 * worker removes the blocks in reverse order before its next task starts, one per tick, and takes them back
 * into its inventory. A pillar is taken down from the top while standing on it, so each step is a one-block
 * drop. A block that is no longer the one placed is dropped from the record unbroken: the worker never removes
 * a block it didn't place.
 *
 * <p>Persisted with the worker and its level, so a reload still cleans up; a loaded record is never owned,
 * and is dropped in any other level.
 */
public final class WorkerScaffold {
	/** Ticks spent on one block before it is abandoned, so cleanup can't wedge the worker forever. */
	public static final int ENTRY_BUDGET = 200;
	private static final double SPEED = 1.0;

	/**
	 * A placed block. {@code standAt} is empty for a pillar block, which is removed by standing on it; for a
	 * bridge block it is the feet spot to stand at while removing it (the previous block's top, or the anchor).
	 */
	public record ScaffoldBlock(BlockPos pos, Block block, Optional<BlockPos> standAt) {
		public static final Codec<ScaffoldBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(ScaffoldBlock::pos),
				BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(ScaffoldBlock::block),
				BlockPos.CODEC.optionalFieldOf("stand_at").forGetter(ScaffoldBlock::standAt))
				.apply(instance, ScaffoldBlock::new));

		public ScaffoldBlock {
			pos = pos.immutable();
			standAt = standAt.map(BlockPos::immutable);
		}
	}

	private final List<ScaffoldBlock> placed = new ArrayList<>();
	private boolean owned;
	/** Walks the worker back in reach of {@link #approachFor}; null when not walking. */
	@Nullable
	private Approach approach;
	@Nullable
	private ScaffoldBlock approachFor;
	private int entryTicks;
	/** A pillar block was just broken under the worker; wait for it to land before the next. */
	private boolean landing;

	/** An Approach is scaffolding: cleanup waits until it {@link #release releases} the record. */
	public void claim() {
		owned = true;
	}

	public void record(ScaffoldBlock block) {
		placed.add(block);
	}

	/** The Approach stopped: the placed blocks are cleaned up from the next tick on. */
	public void release() {
		owned = false;
	}

	/** Read-only view of the placed blocks, in placement order. */
	public List<ScaffoldBlock> placed() {
		return Collections.unmodifiableList(placed);
	}

	public boolean needsCleanup() {
		return !owned && !placed.isEmpty();
	}

	/**
	 * Replaces the record with a loaded one, kept only as far as {@link #sanitize} trusts it; nothing owns
	 * it, so it is cleaned up first.
	 */
	public void load(List<ScaffoldBlock> blocks, Predicate<Block> usable) {
		reset();
		List<ScaffoldBlock> kept = sanitize(blocks, usable);
		if (kept.size() < blocks.size()) {
			RaboDePalha.LOGGER.warn("Dropped {} of {} saved scaffold blocks that no worker could have placed",
					blocks.size() - kept.size(), blocks.size());
		}
		placed.addAll(kept);
	}

	/**
	 * The longest prefix of a loaded record that one {@link ScaffoldPlanner.Plan} could have placed: blocks
	 * that are {@code usable} as scaffold, either a pillar (each block on top of the last, at most
	 * {@link ScaffoldPlanner#MAX_PILLAR}) or a bridge (each block beside the last at the same height, stood on
	 * from the block before, at most {@link ScaffoldPlanner#MAX_BRIDGE}). Cleanup breaks blocks regardless of
	 * hardness, so a crafted save must not be able to name arbitrary blocks or positions.
	 */
	public static List<ScaffoldBlock> sanitize(List<ScaffoldBlock> blocks, Predicate<Block> usable) {
		List<ScaffoldBlock> kept = new ArrayList<>();
		if (blocks.isEmpty()) {
			return kept;
		}
		boolean bridge = blocks.get(0).standAt().isPresent();
		int cap = bridge ? ScaffoldPlanner.MAX_BRIDGE : ScaffoldPlanner.MAX_PILLAR;
		for (ScaffoldBlock entry : blocks) {
			if (kept.size() >= cap || entry.standAt().isPresent() != bridge || !usable.test(entry.block())) {
				break;
			}
			ScaffoldBlock last = kept.isEmpty() ? null : kept.get(kept.size() - 1);
			boolean fits = bridge ? bridgeFits(entry, last) : last == null || entry.pos().equals(last.pos().above());
			if (!fits) {
				break;
			}
			kept.add(entry);
		}
		return kept;
	}

	/** A bridge block is stood on from the cell above its neighbour, which is the previous block's top. */
	private static boolean bridgeFits(ScaffoldBlock entry, @Nullable ScaffoldBlock last) {
		BlockPos from = entry.standAt().get().below();
		if (from.getY() != entry.pos().getY() || from.distManhattan(entry.pos()) != 1) {
			return false;
		}
		return last == null || from.equals(last.pos());
	}

	/**
	 * Whether {@code state} is still the scaffold {@code block} placed: the same block, or placed dirt that
	 * grass, mycelium or podzol has since spread onto.
	 */
	public static boolean stillPlaced(BlockState state, Block block) {
		if (state.is(block)) {
			return true;
		}
		return block == Blocks.DIRT && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL));
	}

	/** The worker is gone for good: nobody can clean up, so the blocks stay in the world. */
	public void clearOnDeath() {
		reset();
	}

	private void reset() {
		placed.clear();
		owned = false;
		stopApproach(null);
		entryTicks = 0;
		landing = false;
	}

	/** Removes at most one placed block, the last one, or moves the worker toward it. */
	public void tickCleanup(ServerLevel level, WorkerEntity worker) {
		if (placed.isEmpty()) {
			return;
		}
		ScaffoldBlock entry = placed.get(placed.size() - 1);
		if (++entryTicks > ENTRY_BUDGET) {
			RaboDePalha.LOGGER.warn("Worker {} left a scaffold block at {}: could not get to it in {} ticks",
					worker.getStringUUID(), entry.pos().toShortString(), ENTRY_BUDGET);
			pop(worker);
			return;
		}
		if (landing) {
			if (!worker.onGround()) {
				return;
			}
			landing = false;
		}
		if (!level.isLoaded(entry.pos())) {
			return;
		}
		if (!stillPlaced(level.getBlockState(entry.pos()), entry.block())) {
			RaboDePalha.LOGGER.debug("Worker {} skips scaffold at {}: no longer the block it placed",
					worker.getStringUUID(), entry.pos().toShortString());
			pop(worker);
			return;
		}
		if (entry.standAt().isPresent()) {
			// A bridge comes down from its stand spot only, one block back at a time, so the worker ends on the
			// side it started from: broken from the far side, the blocks nearer the anchor would be cut off.
			BlockPos spot = entry.standAt().get();
			if (worker.blockPosition().equals(spot) && worker.onGround()) {
				stopApproach(worker);
				worker.getNavigation().stop();
				breakBlock(level, worker, entry);
				return;
			}
			Vec3 back = Vec3.atBottomCenterOf(spot);
			if (worker.getOnPos().equals(entry.pos()) || worker.position().distanceTo(back) <= Approach.MOVE_REACH) {
				// Close by, or on the block itself: step onto the stand spot before pulling this one out.
				stopApproach(worker);
				worker.getNavigation().stop();
				worker.getMoveControl().setWantedPosition(back.x, back.y, back.z, SPEED);
				return;
			}
		} else if (worker.getOnPos().equals(entry.pos())) {
			stopApproach(worker);
			if (worker.onGround()) {
				breakBlock(level, worker, entry);
				landing = true;
			}
			return;
		} else if (worker.getEyePosition().distanceTo(Vec3.atCenterOf(entry.pos())) <= StandSpots.REACH) {
			stopApproach(worker);
			breakBlock(level, worker, entry);
			return;
		}
		if (approach == null || approachFor != entry) {
			stopApproach(worker);
			approach = entry.standAt()
					.map(spot -> Approach.toStandAt(spot, ENTRY_BUDGET))
					.orElseGet(() -> Approach.toWorkOn(entry.pos(), ENTRY_BUDGET));
			approachFor = entry;
			approach.start(worker);
		}
		Approach.State state = approach.tick(worker);
		if (state == Approach.State.ARRIVED) {
			stopApproach(worker);
		} else if (state == Approach.State.FAILED) {
			RaboDePalha.LOGGER.warn("Worker {} left a scaffold block at {}: {}", worker.getStringUUID(),
					entry.pos().toShortString(), approach.failure());
			pop(worker);
		}
	}

	private void breakBlock(ServerLevel level, WorkerEntity worker, ScaffoldBlock entry) {
		worker.getLookControl().setLookAt(Vec3.atCenterOf(entry.pos()));
		level.destroyBlock(entry.pos(), false, worker);
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		worker.insertOrDrop(new ItemStack(entry.block().asItem()));
		worker.resetFallDistance();
		pop(worker);
	}

	private void pop(WorkerEntity worker) {
		placed.remove(placed.size() - 1);
		entryTicks = 0;
		stopApproach(worker);
	}

	private void stopApproach(@Nullable WorkerEntity worker) {
		if (approach != null && worker != null) {
			approach.stop(worker);
		}
		approach = null;
		approachFor = null;
	}
}
