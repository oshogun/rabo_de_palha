package io.github.oshogun.rabodepalha.rts;

/**
 * The pages of the RTS command bar, in tab order. Each command names its page; the bar shows the current page's
 * buttons, plus the {@link #GLOBAL} commands, which are on every page. A page with no commands isn't shown, so the
 * pages below can be declared before their commands land.
 *
 * <p>Where the environment commands are expected to go, each card picking the final page under the
 * {@link #MAX_SLOTS} cap: {@link #FARM} gets plant, harvest, tend, fertilize, compost, ranch, bucket and fish;
 * {@link #GATHER} gets fell, vein, collect and use; {@link #MAKE} gets smelt, craft, sort and storage;
 * {@link #WORKER} gets stance and home.
 */
public enum RtsCommandPage {
	/** Move, mine, place, dig and spawn worker. */
	ORDERS("orders"),
	/** Deposit, withdraw and build. */
	HAUL_BUILD("haul_build"),
	FARM("farm"),
	GATHER("gather"),
	MAKE("make"),
	WORKER("worker"),
	/** Not a page: commands shown on every page, after the page's buttons, such as Stop. They have no slot. */
	GLOBAL("global");

	/** The most commands a page holds: one per slot key, 1 to 9. */
	public static final int MAX_SLOTS = 9;

	private final String id;

	RtsCommandPage(String id) {
		this.id = id;
	}

	/** The page's id, used in its lang key. */
	public String id() {
		return id;
	}

	/** The lang key of the page's tab name. */
	public String langKey() {
		return "rabodepalha.rts.page." + id;
	}
}
