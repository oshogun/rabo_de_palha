package io.github.oshogun.rabodepalha.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ground navigation whose paths go through closed wooden doors and fence gates, and through closed
 * iron doors that a nearby switch looks like it opens (see {@link WorkerNodeEvaluator}). Other iron
 * doors stay blocked. Paths also climb up and down columns of climbable blocks ({@link ClimbLinks}).
 *
 * <p>Climbing reuses vanilla movement: a mob inside a climbable block rises at 0.2 per tick while it
 * jumps or pushes into a wall, and slides down at most 0.15 per tick otherwise, with no fall damage.
 * After the vanilla tick, a climb step up jumps and aims at the column centre, nudged toward the
 * ladder's wall when there is one. On the top cell the next node is the floor beside the column; the
 * vanilla target is kept and the worker keeps jumping, so the push into the floor lip carries it over.
 * A climb step down walks the worker over the top of the shaft, then, once it is in the column, leaves
 * the move control waiting so it slides down: the move control jumps whenever the mob stands in a block
 * with a collision shape, as a ladder has, which would carry it back up. It does the same while the next
 * node is a floor below the column and the worker is off the ground. Going down it sneaks, which scaffolding needs to let a mob down. The
 * sneak is cleared as soon as the worker is off a down step, and when the path stops. On climb steps a
 * node is passed only once reached, since vanilla's corner cutting would skip the column's nodes.
 */
public class WorkerNavigation extends GroundPathNavigation {
	/** How far from the column centre an upward climber aims toward the wall, so it presses into it. */
	private static final double WALL_PUSH = 0.4;

	/** Whether this navigation set the worker sneaking, so it only ever clears its own sneak. */
	private boolean sneaking;

	public WorkerNavigation(Mob mob, Level level) {
		super(mob, level);
	}

	@Override
	protected PathFinder createPathFinder(int maxVisitedNodes) {
		// The super constructor calls this before field initialisers run, so the flags are set here.
		this.nodeEvaluator = new WorkerNodeEvaluator();
		this.nodeEvaluator.setCanPassDoors(true);
		this.nodeEvaluator.setCanOpenDoors(true);
		return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
	}

	@Override
	protected boolean canUpdatePath() {
		// Off the ground on a ladder the path must still advance and repath.
		return super.canUpdatePath() || this.mob.onClimbable();
	}

	@Override
	public void tick() {
		super.tick();
		driveClimb();
	}

	@Override
	protected void followThePath() {
		if (!onClimbStep()) {
			super.followThePath();
			return;
		}
		// Vanilla cuts corners toward a node up to two blocks away, which skips the nodes of a climb column and
		// leaves the worker standing at the top of the shaft. On a climb step, a node is passed only once reached.
		this.maxDistanceToWaypoint = this.mob.getBbWidth() > 0.75F ? this.mob.getBbWidth() / 2.0F : 0.75F - this.mob.getBbWidth() / 2.0F;
		BlockPos next = this.path.getNextNodePos();
		if (Math.abs(this.mob.getX() - (next.getX() + 0.5)) < this.maxDistanceToWaypoint
				&& Math.abs(this.mob.getZ() - (next.getZ() + 0.5)) < this.maxDistanceToWaypoint
				&& Math.abs(this.mob.getY() - next.getY()) < this.getMaxVerticalDistanceToWaypoint()) {
			this.path.advance();
		}
		this.doStuckDetection(this.getTempMobPos());
	}

	/** Whether the path step into the next node, or the one after it, goes straight up or down a column. */
	private boolean onClimbStep() {
		int index = this.path.getNextNodeIndex();
		BlockPos next = this.path.getNextNodePos();
		if (index > 0 && ClimbLinks.segment(this.path.getNodePos(index - 1), next) != ClimbLinks.Segment.NONE) {
			return true;
		}
		return index + 1 < this.path.getNodeCount()
				&& ClimbLinks.segment(next, this.path.getNodePos(index + 1)) != ClimbLinks.Segment.NONE;
	}

	@Override
	public void stop() {
		super.stop();
		setSneaking(false);
	}

	private void driveClimb() {
		Path path = this.path;
		if (path == null || path.isDone()) {
			setSneaking(false);
			return;
		}
		BlockPos next = path.getNextNodePos();
		BlockPos here = this.mob.blockPosition();
		int index = path.getNextNodeIndex();
		BlockPos from = index > 0 ? path.getNodePos(index - 1) : here;
		ClimbLinks.Segment segment = ClimbLinks.segment(from, next);

		if (segment == ClimbLinks.Segment.UP) {
			this.mob.getJumpControl().jump();
			double x = next.getX() + 0.5;
			double z = next.getZ() + 0.5;
			Direction wall = ClimbLinks.wallDirection(this.level.getBlockState(here));
			if (wall != null) {
				x += wall.getStepX() * WALL_PUSH;
				z += wall.getStepZ() * WALL_PUSH;
			}
			this.mob.getMoveControl().setWantedPosition(x, next.getY(), z, this.speedModifier);
			setSneaking(false);
			return;
		}
		if (this.mob.onClimbable() && next.getY() > here.getY()) {
			// Top of the column: vanilla already aims at the floor beside it; jumping lifts over the lip.
			this.mob.getJumpControl().jump();
			setSneaking(false);
			return;
		}
		boolean intoColumn = segment == ClimbLinks.Segment.DOWN
				|| next.getY() < here.getY() && (ClimbLinks.climbCell(this.level::getBlockState, next)
						|| ClimbLinks.isClimbTrapdoor(this.level::getBlockState, next));
		// Only off the ground is the worker really mid-column. On the ground in a climbable cell, as in a ground
		// vine or scaffolding, a step down is an ordinary walk that vanilla's wanted position already handles.
		if (intoColumn || this.mob.onClimbable() && !this.mob.onGround() && next.getY() < here.getY()) {
			boolean inColumn = here.getX() == next.getX() && here.getZ() == next.getZ();
			if (intoColumn && !(inColumn && this.mob.onClimbable())) {
				// Stepping from the floor over the top of the shaft, until the worker drops into it.
				this.mob.getMoveControl().setWantedPosition(next.getX() + 0.5, this.mob.getY(), next.getZ() + 0.5,
						this.speedModifier);
			} else {
				// In the column, or above the floor the path steps off onto: the move control jumps whenever the
				// worker stands in a block with a collision shape, as a ladder has, which would carry it back up.
				// Waiting lets it slide down.
				this.mob.getMoveControl().setWait();
			}
			setSneaking(true);
			return;
		}
		setSneaking(false);
	}

	private void setSneaking(boolean sneak) {
		if (sneak) {
			this.mob.setShiftKeyDown(true);
			this.sneaking = true;
		} else if (this.sneaking) {
			this.mob.setShiftKeyDown(false);
			this.sneaking = false;
		}
	}
}
