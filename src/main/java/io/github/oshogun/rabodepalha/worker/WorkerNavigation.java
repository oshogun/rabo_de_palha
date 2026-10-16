package io.github.oshogun.rabodepalha.worker;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ground navigation whose paths go through closed wooden doors and fence gates, and through closed
 * iron doors that a nearby switch looks like it opens (see {@link WorkerNodeEvaluator}). Other iron
 * doors stay blocked.
 */
public class WorkerNavigation extends GroundPathNavigation {
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
}
