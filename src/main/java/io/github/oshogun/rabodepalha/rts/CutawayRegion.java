package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Pure rules for the RTS cutaway: the region of the world that is not drawn. It is the union of a few
 * {@link CutawayShape}s: everything above the view level in Level mode, plus the local cuts of #29 (the
 * see-through bubble, roofs off, walls down). It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>Render sections are classified against an inflated block span {@code [s*16-1, s*16+16]}: a section's
 * mesh also reads the blocks just next to it for face culling, so a section whose neighbour blocks are cut
 * must be re-meshed too.
 *
 * <p>Regions are immutable and read from mesh threads and Sodium's cull thread, so {@link #contains} and
 * {@link #classify} loop over a final array and never allocate.
 */
public final class CutawayRegion {
	/** No cutaway: nothing is hidden. */
	public static final CutawayRegion NONE = new CutawayRegion(null, new CutawayShape[0]);

	/** How a render section relates to the region. */
	public enum Section {
		/** Nothing in or next to the section is hidden; vanilla meshes it as usual. */
		OUTSIDE,
		/** The cut crosses the section or its neighbour blocks; it is re-meshed with the cut applied. */
		BOUNDARY,
		/** The whole section is hidden; it is skipped without re-meshing. */
		INSIDE
	}

	/** Receives the section coordinates of a section to re-mesh. */
	@FunctionalInterface
	public interface SectionSink {
		void accept(int sx, int sy, int sz);
	}

	private final @Nullable Integer level;
	private final CutawayShape[] shapes;

	private CutawayRegion(@Nullable Integer level, CutawayShape[] shapes) {
		this.level = level;
		this.shapes = shapes;
	}

	/** The region of every block with y above {@code viewLevel}. */
	public static CutawayRegion above(int viewLevel) {
		return of(viewLevel, List.of());
	}

	/**
	 * Everything above {@code viewLevel}, when it is not null, together with {@code shapes}. With no level
	 * and no shapes this is {@link #NONE}.
	 */
	public static CutawayRegion of(@Nullable Integer viewLevel, List<? extends CutawayShape> shapes) {
		List<CutawayShape> all = new ArrayList<>(shapes.size() + 1);
		if (viewLevel != null) {
			all.add(new CutawayShape.Above(viewLevel));
		}
		all.addAll(shapes);
		if (all.isEmpty()) {
			return NONE;
		}
		return new CutawayRegion(viewLevel, all.toArray(new CutawayShape[0]));
	}

	/** Whether anything is hidden. */
	public boolean active() {
		return shapes.length > 0;
	}

	/** The view level, or {@code null} when the region does not cut everything above a level. */
	public @Nullable Integer level() {
		return level;
	}

	/** The shapes, in the order they were given, the level first. */
	public List<CutawayShape> shapes() {
		return List.of(shapes);
	}

	/** Whether the block at {@code x, y, z} is hidden. */
	public boolean contains(int x, int y, int z) {
		for (CutawayShape shape : shapes) {
			if (shape.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How the render section at section coordinates {@code sx, sy, sz} relates to the region: INSIDE when a
	 * shape hides all of it, else BOUNDARY when a shape reaches into it, else OUTSIDE.
	 */
	public Section classify(int sx, int sy, int sz) {
		Section result = Section.OUTSIDE;
		for (CutawayShape shape : shapes) {
			Section section = shape.classify(sx, sy, sz);
			if (section == Section.INSIDE) {
				return Section.INSIDE;
			}
			if (section == Section.BOUNDARY) {
				result = Section.BOUNDARY;
			}
		}
		return result;
	}

	/**
	 * Whether an entity whose feet are in block {@code feetX, feetY, feetZ} is hidden, so it is neither drawn
	 * nor picked. Only the level, rooms and walls hide entities; the bubble keeps the workers in it visible.
	 */
	public boolean hidesEntityAt(int feetX, int feetY, int feetZ) {
		for (CutawayShape shape : shapes) {
			if (shape.hidesEntities() && shape.contains(feetX, feetY, feetZ)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells {@code dirty} which sections in {@code window} must be re-meshed when the region changes from
	 * {@code old} to {@code now}, and returns whether the set of {@link Section#INSIDE} sections changed there,
	 * so the visible-section graph must be rebuilt.
	 *
	 * <p>Only sections the changed shapes reach can change: elsewhere both regions hide the same blocks. Of
	 * those, the ones that are {@link Section#BOUNDARY} on either side are re-meshed, since their cut mesh is
	 * stale or due. A section that only moves between {@link Section#OUTSIDE} and {@link Section#INSIDE} keeps
	 * its real mesh and is just hidden or shown.
	 */
	public static boolean diff(CutawayRegion old, CutawayRegion now, CutawayShape.SectionBounds window,
			SectionSink dirty) {
		List<CutawayShape.SectionBounds> candidates = new ArrayList<>();
		addChanged(old, now, window, candidates);
		addChanged(now, old, window, candidates);
		boolean insideChanged = false;
		for (int i = 0; i < candidates.size(); i++) {
			CutawayShape.SectionBounds box = candidates.get(i);
			for (int sy = box.minY(); sy <= box.maxY(); sy++) {
				for (int sz = box.minZ(); sz <= box.maxZ(); sz++) {
					for (int sx = box.minX(); sx <= box.maxX(); sx++) {
						if (coveredEarlier(candidates, i, sx, sy, sz)) {
							continue;
						}
						Section before = old.classify(sx, sy, sz);
						Section after = now.classify(sx, sy, sz);
						if (before == Section.BOUNDARY || after == Section.BOUNDARY) {
							dirty.accept(sx, sy, sz);
						}
						insideChanged |= (before == Section.INSIDE) != (after == Section.INSIDE);
					}
				}
			}
		}
		return insideChanged;
	}

	/** Adds the window-clipped bounds of the shapes of {@code from} that {@code to} doesn't have. */
	private static void addChanged(CutawayRegion from, CutawayRegion to, CutawayShape.SectionBounds window,
			List<CutawayShape.SectionBounds> out) {
		for (CutawayShape shape : from.shapes) {
			if (to.has(shape)) {
				continue;
			}
			CutawayShape.SectionBounds bounds = shape.sectionBounds().intersect(window);
			if (bounds != null) {
				out.add(bounds);
			}
		}
	}

	private boolean has(CutawayShape shape) {
		for (CutawayShape mine : shapes) {
			if (mine.equals(shape)) {
				return true;
			}
		}
		return false;
	}

	private static boolean coveredEarlier(List<CutawayShape.SectionBounds> boxes, int index, int sx, int sy, int sz) {
		for (int j = 0; j < index; j++) {
			if (boxes.get(j).contains(sx, sy, sz)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof CutawayRegion other && Arrays.equals(shapes, other.shapes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(shapes);
	}

	@Override
	public String toString() {
		return active() ? "CutawayRegion" + Arrays.toString(shapes) : "CutawayRegion[NONE]";
	}
}
