package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The command bar's commands sorted into pages: which pages are shown, which command each slot key presses on a
 * page, and how the page key cycles. Commands keep registry order within their page. Generic over the command
 * type so it stays free of client classes.
 *
 * @param <T> the command type
 */
public final class RtsCommandPages<T> {
	private final Map<RtsCommandPage, List<T>> byPage = new EnumMap<>(RtsCommandPage.class);
	private final List<RtsCommandPage> visible;

	/**
	 * Sorts {@code commands} into pages.
	 *
	 * @throws IllegalStateException if a page holds more than {@link RtsCommandPage#MAX_SLOTS} commands
	 */
	public RtsCommandPages(List<T> commands, Function<T, RtsCommandPage> pageOf) {
		for (RtsCommandPage page : RtsCommandPage.values()) {
			byPage.put(page, new ArrayList<>());
		}
		for (T command : commands) {
			byPage.get(pageOf.apply(command)).add(command);
		}
		List<RtsCommandPage> shown = new ArrayList<>();
		for (RtsCommandPage page : RtsCommandPage.values()) {
			List<T> onPage = byPage.get(page);
			byPage.put(page, List.copyOf(onPage));
			if (page == RtsCommandPage.GLOBAL || onPage.isEmpty()) {
				continue;
			}
			if (onPage.size() > RtsCommandPage.MAX_SLOTS) {
				throw new IllegalStateException("RTS command page " + page.id() + " has " + onPage.size()
						+ " commands, more than " + RtsCommandPage.MAX_SLOTS + "; add a page");
			}
			shown.add(page);
		}
		visible = List.copyOf(shown);
	}

	/** The pages that have at least one command, in tab order; never {@link RtsCommandPage#GLOBAL}. */
	public List<RtsCommandPage> visiblePages() {
		return visible;
	}

	/** The commands on {@code page}, in slot order. */
	public List<T> commands(RtsCommandPage page) {
		return byPage.get(page);
	}

	/** The commands shown on every page. */
	public List<T> globals() {
		return byPage.get(RtsCommandPage.GLOBAL);
	}

	/** The command slot key {@code slot} (1 to 9) presses on {@code page}, or empty past the page's size. */
	public Optional<T> slot(RtsCommandPage page, int slot) {
		if (page == RtsCommandPage.GLOBAL || slot < 1 || slot > RtsCommandPage.MAX_SLOTS) {
			return Optional.empty();
		}
		List<T> onPage = byPage.get(page);
		return slot <= onPage.size() ? Optional.of(onPage.get(slot - 1)) : Optional.empty();
	}

	/** The 1-based slot of {@code command} on {@code page}, or 0 when it isn't on that page. */
	public int slotOf(RtsCommandPage page, T command) {
		if (page == RtsCommandPage.GLOBAL) {
			return 0;
		}
		return byPage.get(page).indexOf(command) + 1;
	}

	/** The first shown page; {@link RtsCommandPage#ORDERS} when no page has commands. */
	public RtsCommandPage first() {
		return visible.isEmpty() ? RtsCommandPage.ORDERS : visible.get(0);
	}

	/** {@code page} if it is shown, otherwise the first shown page. */
	public RtsCommandPage valid(RtsCommandPage page) {
		return visible.contains(page) ? page : first();
	}

	/** The shown page after {@code page}, wrapping to the first; the first one if {@code page} isn't shown. */
	public RtsCommandPage next(RtsCommandPage page) {
		int i = visible.indexOf(page);
		if (i < 0) {
			return first();
		}
		return visible.get((i + 1) % visible.size());
	}

	/** The shown page before {@code page}, wrapping to the last; the first one if {@code page} isn't shown. */
	public RtsCommandPage previous(RtsCommandPage page) {
		int i = visible.indexOf(page);
		if (i < 0) {
			return first();
		}
		return visible.get((i - 1 + visible.size()) % visible.size());
	}
}
