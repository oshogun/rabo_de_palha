package io.github.oshogun.rabodepalha.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Walk evaluator that treats a closed fence gate like a closed wooden door, so a worker that can
 * open doors plans paths through it ({@code WorkerDoors} opens it on the way).
 *
 * <p>Only the gate's own cell changes; the cell above keeps its vanilla type, so a closed gate still
 * can't be stepped onto.
 *
 * <p>A closed iron door gets the same treatment when {@link IronDoorSwitches} finds a switch near it
 * and the worker hasn't refused that door ({@link WorkerIronDoors} uses the switch on the way). The
 * path type has no direction, so {@link #getNeighbors} drops steps into the door from a side without
 * a switch, and charges {@link IronDoorSwitches#IRON_DOOR_MALUS} for the others so an open route of
 * similar length wins.
 *
 * <p>Workers also climb: {@link #getNeighbors} adds one node per climbed block, up and down a column
 * of climbable blocks, from a floor down into the top of a shaft beside it and back off the top onto that
 * floor, following the rules in
 * {@link ClimbLinks}. Each climb node costs {@link ClimbLinks#CLIMB_MALUS}. {@link #getStart} starts a
 * worker that is on a ladder where it is, not at the bottom of the shaft. Mobs other than workers keep
 * the vanilla behaviour.
 */
public class WorkerNodeEvaluator extends WalkNodeEvaluator {
	/** Whether a door, by canonical position, has a switch on either side; cleared per search. */
	private final Map<BlockPos, Boolean> switchedCache = new HashMap<>();

	@Override
	public void prepare(PathNavigationRegion level, Mob entity) {
		switchedCache.clear();
		super.prepare(level, entity);
	}

	@Override
	public void done() {
		switchedCache.clear();
		super.done();
	}

	@Override
	public PathType getPathType(PathfindingContext context, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = context.getBlockState(pos);
		if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
			return PathType.DOOR_WOOD_CLOSED;
		}
		if (isClosedIronDoor(state) && isSwitchedDoor(context, state, pos)) {
			return PathType.DOOR_WOOD_CLOSED;
		}
		return super.getPathType(context, x, y, z);
	}

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		if (!(this.mob instanceof WorkerEntity) || this.currentContext == null) {
			return count;
		}
		int kept = 0;
		for (int i = 0; i < count; i++) {
			Node neighbor = neighbors[i];
			BlockPos pos = new BlockPos(neighbor.x, neighbor.y, neighbor.z);
			BlockState state = this.currentContext.getBlockState(pos);
			if (isClosedIronDoor(state)) {
				BlockPos door = Openables.canonicalPos(state, pos);
				Direction.Axis axis = IronDoorSwitches.passageAxis(state);
				int side = IronDoorSwitches.sideOf(door, axis, node.x + 0.5, node.z + 0.5);
				if (side == 0 || IronDoorSwitches.switchesOnSide(door, axis, side, this.currentContext::getBlockState).isEmpty()) {
					continue;
				}
				// Nodes are cached per search, so max keeps this idempotent.
				neighbor.costMalus = Math.max(neighbor.costMalus, IronDoorSwitches.IRON_DOOR_MALUS);
			}
			neighbors[kept++] = neighbor;
		}
		for (int i = kept; i < count; i++) {
			neighbors[i] = null;
		}
		return addClimbLinks(neighbors, kept, node);
	}

	@Override
	public Node getStart() {
		if (this.mob instanceof WorkerEntity && this.mob.onClimbable()) {
			// Vanilla scans down past the pathfindable ladder and would start at the bottom of the shaft.
			Node start = this.getNode(this.mob.blockPosition());
			start.type = PathType.WALKABLE;
			start.costMalus = 0.0f;
			return start;
		}
		return super.getStart();
	}

	/**
	 * Appends the climb links of {@link ClimbLinks} from {@code node}: up, down, down into a shaft beside it, and
	 * off the top of a column onto the floor beside it.
	 */
	private int addClimbLinks(Node[] neighbors, int count, Node node) {
		Function<BlockPos, BlockState> get = this.currentContext::getBlockState;
		BlockPos feet = new BlockPos(node.x, node.y, node.z);
		if (ClimbLinks.canClimbUp(get, feet, ClimbLinks.MAX_CLIMB)) {
			count = addClimbLink(neighbors, count, feet.above());
		}
		if (ClimbLinks.canClimbDown(get, feet)) {
			count = addClimbLink(neighbors, count, feet.below());
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(direction);
			if (ClimbLinks.climbEntryBelow(get, side)) {
				count = addClimbLink(neighbors, count, side.below());
			}
			if (ClimbLinks.climbExitAbove(get, feet, side)) {
				count = addClimbLink(neighbors, count, side.above());
			}
		}
		return count;
	}

	private int addClimbLink(Node[] neighbors, int count, BlockPos pos) {
		if (count >= neighbors.length) {
			return count;
		}
		Node link = this.getNode(pos.getX(), pos.getY(), pos.getZ());
		if (link.closed) {
			return count;
		}
		for (int i = 0; i < count; i++) {
			if (neighbors[i] == link) {
				return count;
			}
		}
		link.type = PathType.WALKABLE;
		// Nodes are cached per search, so max keeps this idempotent; it also lifts a vanilla -1.
		link.costMalus = Math.max(link.costMalus, ClimbLinks.CLIMB_MALUS);
		neighbors[count++] = link;
		return count;
	}

	private static boolean isClosedIronDoor(BlockState state) {
		return IronDoorSwitches.isIronDoor(state) && !state.getValue(DoorBlock.OPEN);
	}

	/** Whether the worker searching may plan through this closed iron door. */
	private boolean isSwitchedDoor(PathfindingContext context, BlockState state, BlockPos pos) {
		if (!(this.mob instanceof WorkerEntity worker) || worker.level().isClientSide()) {
			return false;
		}
		BlockPos door = Openables.canonicalPos(state, pos);
		if (worker.ironDoors().isRefused(door, worker.level().getGameTime())) {
			return false;
		}
		Direction.Axis axis = IronDoorSwitches.passageAxis(state);
		return switchedCache.computeIfAbsent(door,
				d -> IronDoorSwitches.hasAnySwitch(d, axis, context::getBlockState));
	}
}
