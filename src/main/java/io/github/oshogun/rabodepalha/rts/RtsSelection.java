package io.github.oshogun.rabodepalha.rts;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The set of selected workers in RTS mode, by entity id, plus the click and box rules that change it.
 * It has no Minecraft dependencies, so the rules can be unit-tested. Ids keep their selection order.
 */
public final class RtsSelection {
	private final LinkedHashSet<Integer> ids = new LinkedHashSet<>();

	/** Selects only {@code id}. */
	public void replace(int id) {
		ids.clear();
		ids.add(id);
	}

	/** Selects exactly {@code newIds}. */
	public void replaceAll(Collection<Integer> newIds) {
		ids.clear();
		ids.addAll(newIds);
	}

	/** Adds {@code id} if it is not selected, removes it otherwise. */
	public void toggle(int id) {
		if (!ids.remove(id)) {
			ids.add(id);
		}
	}

	public void addAll(Collection<Integer> newIds) {
		ids.addAll(newIds);
	}

	public void clear() {
		ids.clear();
	}

	public boolean contains(int id) {
		return ids.contains(id);
	}

	public int size() {
		return ids.size();
	}

	public boolean isEmpty() {
		return ids.isEmpty();
	}

	/** A copy of the selected ids, in selection order. */
	public List<Integer> ids() {
		return List.copyOf(ids);
	}

	/** Keeps only the ids that {@code keep} accepts, e.g. to drop workers that are gone. */
	public void retainIf(IntPredicate keep) {
		ids.removeIf(id -> !keep.test(id));
	}

	/**
	 * Applies a single click. Without shift, a hit selects only that worker and a miss clears the
	 * selection. With shift, a hit toggles that worker and a miss leaves the selection alone.
	 */
	public void clickSelect(Integer hitOrNull, boolean shift) {
		if (shift) {
			if (hitOrNull != null) {
				toggle(hitOrNull);
			}
		} else if (hitOrNull != null) {
			replace(hitOrNull);
		} else {
			clear();
		}
	}

	/** Applies a box drag. Without shift the box replaces the selection; with shift it adds to it. */
	public void boxSelect(Collection<Integer> hits, boolean shift) {
		if (shift) {
			addAll(hits);
		} else {
			replaceAll(hits);
		}
	}
}
