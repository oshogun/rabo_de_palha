package io.github.oshogun.rabodepalha.rts;

import io.github.oshogun.rabodepalha.rts.CutawayRegion.Section;

/**
 * One piece of the RTS cutaway: a set of hidden blocks that {@link CutawayRegion} unions with the others.
 * Shapes are immutable records with value equality, so a region that did not change compares equal and
 * re-meshes nothing. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>{@link #contains} and {@link #classify} run on mesh threads and on Sodium's cull thread: they must not
 * allocate or touch shared state. {@link #classify} uses the section's inflated span
 * {@code [s*16-1, s*16+16]} on every axis, because a section's mesh also reads its neighbour blocks.
 */
public sealed interface CutawayShape {
	int SECTION_SIZE = 16;

	/** Whether the block at {@code x, y, z} is hidden. */
	boolean contains(int x, int y, int z);

	/** How the render section at section coordinates {@code sx, sy, sz} relates to this shape. */
	Section classify(int sx, int sy, int sz);

	/** Whether entities whose feet block is in this shape are hidden and can't be picked. */
	boolean hidesEntities();

	/** Every section whose classification is not {@link Section#OUTSIDE}, possibly more. */
	SectionBounds sectionBounds();

	/** An inclusive box of section coordinates; unbounded axes use the int extremes. */
	record SectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		/** The overlap with {@code other}, or {@code null} when they don't overlap. */
		public SectionBounds intersect(SectionBounds other) {
			int x0 = Math.max(minX, other.minX);
			int y0 = Math.max(minY, other.minY);
			int z0 = Math.max(minZ, other.minZ);
			int x1 = Math.min(maxX, other.maxX);
			int y1 = Math.min(maxY, other.maxY);
			int z1 = Math.min(maxZ, other.maxZ);
			return x0 > x1 || y0 > y1 || z0 > z1 ? null : new SectionBounds(x0, y0, z0, x1, y1, z1);
		}

		public boolean contains(int sx, int sy, int sz) {
			return sx >= minX && sx <= maxX && sy >= minY && sy <= maxY && sz >= minZ && sz <= maxZ;
		}

		/** The sections whose inflated span reaches a block in {@code [lo, hi]}, on one axis. */
		static int lowSection(int lo) {
			return Math.floorDiv(lo - SECTION_SIZE, SECTION_SIZE);
		}

		static int highSection(int hi) {
			return Math.floorDiv(hi + 1, SECTION_SIZE);
		}
	}

	/** Every block with y above {@code level}: the global view level of #27. */
	record Above(int level) implements CutawayShape {
		@Override
		public boolean contains(int x, int y, int z) {
			return y > level;
		}

		@Override
		public Section classify(int sx, int sy, int sz) {
			int bottom = sy * SECTION_SIZE - 1;
			int top = sy * SECTION_SIZE + SECTION_SIZE;
			if (bottom > level) {
				return Section.INSIDE;
			}
			if (top <= level) {
				return Section.OUTSIDE;
			}
			return Section.BOUNDARY;
		}

		/** Entities above the level are hidden, as {@link RtsViewLevel#feetAboveLevel} picks them. */
		@Override
		public boolean hidesEntities() {
			return true;
		}

		@Override
		public SectionBounds sectionBounds() {
			return new SectionBounds(Integer.MIN_VALUE, Math.floorDiv(level, SECTION_SIZE), Integer.MIN_VALUE,
					Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
		}
	}

	/**
	 * An inclusive box of blocks, for a room's roof or a wall. {@code maxY} may be {@link Integer#MAX_VALUE}
	 * to reach the top of the world.
	 */
	record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements CutawayShape {
		@Override
		public boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}

		@Override
		public Section classify(int sx, int sy, int sz) {
			int x0 = sx * SECTION_SIZE - 1;
			int y0 = sy * SECTION_SIZE - 1;
			int z0 = sz * SECTION_SIZE - 1;
			int x1 = sx * SECTION_SIZE + SECTION_SIZE;
			int y1 = sy * SECTION_SIZE + SECTION_SIZE;
			int z1 = sz * SECTION_SIZE + SECTION_SIZE;
			if (x1 < minX || x0 > maxX || y1 < minY || y0 > maxY || z1 < minZ || z0 > maxZ) {
				return Section.OUTSIDE;
			}
			if (x0 >= minX && x1 <= maxX && y0 >= minY && y1 <= maxY && z0 >= minZ && z1 <= maxZ) {
				return Section.INSIDE;
			}
			return Section.BOUNDARY;
		}

		/** Rooms and walls hide the entities in them, like the level does. */
		@Override
		public boolean hidesEntities() {
			return true;
		}

		@Override
		public SectionBounds sectionBounds() {
			return new SectionBounds(SectionBounds.lowSection(minX), SectionBounds.lowSection(minY),
					SectionBounds.lowSection(minZ), SectionBounds.highSection(maxX),
					maxY >= Integer.MAX_VALUE - SECTION_SIZE ? Integer.MAX_VALUE : SectionBounds.highSection(maxY),
					SectionBounds.highSection(maxZ));
		}
	}

	/**
	 * The see-through bubble: an oblique vertical cylinder of {@code radius} blocks, {@code height} blocks
	 * tall from the anchor block {@code ax, ay, az} up. At block y its disc is centred on
	 * {@code (ax + dxPerY * (y - ay), az + dzPerY * (y - ay))}, so it leans toward the camera. A block is in
	 * the bubble when its centre is within {@code radius} of the disc centre. The block under the anchor,
	 * the floor, is never in it.
	 */
	record Bubble(int ax, int ay, int az, double radius, int height, double dxPerY, double dzPerY)
			implements CutawayShape {
		@Override
		public boolean contains(int x, int y, int z) {
			int t = y - ay;
			if (t < 0 || t >= height) {
				return false;
			}
			double u = x - ax - dxPerY * t;
			double w = z - az - dzPerY * t;
			return u * u + w * w <= radius * radius;
		}

		/**
		 * Exact for {@link Section#INSIDE} (every block of the inflated span is tested at its layer's disc) and
		 * conservative for {@link Section#OUTSIDE} (the span's rectangle is closer to no disc centre than the
		 * radius), so a mixed section is always {@link Section#BOUNDARY}.
		 */
		@Override
		public Section classify(int sx, int sy, int sz) {
			int y0 = sy * SECTION_SIZE - 1;
			int y1 = sy * SECTION_SIZE + SECTION_SIZE;
			int t0 = Math.max(y0, ay) - ay;
			int t1 = Math.min(y1, ay + height - 1) - ay;
			if (t0 > t1) {
				return Section.OUTSIDE;
			}
			// Block offsets from the anchor column, on the inflated span.
			double u0 = sx * SECTION_SIZE - 1 - ax;
			double u1 = sx * SECTION_SIZE + SECTION_SIZE - ax;
			double w0 = sz * SECTION_SIZE - 1 - az;
			double w1 = sz * SECTION_SIZE + SECTION_SIZE - az;
			double r2 = radius * radius;
			boolean any = false;
			boolean all = y0 >= ay && y1 <= ay + height - 1;
			for (int t = t0; t <= t1; t++) {
				double cu = dxPerY * t;
				double cw = dzPerY * t;
				double nu = Math.max(Math.max(u0 - cu, cu - u1), 0.0);
				double nw = Math.max(Math.max(w0 - cw, cw - w1), 0.0);
				if (nu * nu + nw * nw <= r2) {
					any = true;
				}
				if (all) {
					double fu = Math.max(cu - u0, u1 - cu);
					double fw = Math.max(cw - w0, w1 - cw);
					if (fu * fu + fw * fw > r2) {
						all = false;
					}
				}
				if (any && !all) {
					return Section.BOUNDARY;
				}
			}
			if (!any) {
				return Section.OUTSIDE;
			}
			return all ? Section.INSIDE : Section.BOUNDARY;
		}

		/** Workers and mobs inside the bubble stay visible and clickable. */
		@Override
		public boolean hidesEntities() {
			return false;
		}

		@Override
		public SectionBounds sectionBounds() {
			double lean = height - 1;
			int minX = (int) Math.floor(ax + Math.min(0.0, dxPerY * lean) - radius);
			int maxX = (int) Math.ceil(ax + Math.max(0.0, dxPerY * lean) + radius);
			int minZ = (int) Math.floor(az + Math.min(0.0, dzPerY * lean) - radius);
			int maxZ = (int) Math.ceil(az + Math.max(0.0, dzPerY * lean) + radius);
			return new SectionBounds(SectionBounds.lowSection(minX), SectionBounds.lowSection(ay),
					SectionBounds.lowSection(minZ), SectionBounds.highSection(maxX),
					SectionBounds.highSection(ay + height - 1), SectionBounds.highSection(maxZ));
		}
	}
}
