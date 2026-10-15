package io.github.oshogun.rabodepalha.rts;

/**
 * One colour per order kind, shared by the queued-order markers in the world and the command bar's kind
 * strips, so a marker matches the button that gave the order. Colours are ARGB.
 */
public final class RtsOrderColors {
	/** Alpha of a marker's translucent fill. */
	private static final int FILL_ALPHA = 0x33;
	/** Alpha of a queued, not yet current, marker's lines. */
	private static final int DIMMED_ALPHA = 0x80;

	private RtsOrderColors() {
	}

	/** The opaque line colour of {@code kind}. */
	public static int line(RtsTaskKind kind) {
		return switch (kind) {
			case MOVE -> 0xFF4AE8E8;
			case MINE -> 0xFFFF9A2E;
			case PLACE -> 0xFF5AD85A;
			// The same blue as the dig area being marked.
			case DIG -> 0xFF4AA8FF;
			case BUILD -> 0xFFB46CFF;
			case DEPOSIT -> 0xFFD8C878;
			case WITHDRAW -> 0xFFB8A858;
			case USE -> 0xFFFF5AC8;
			case PLANT -> 0xFF9AD82E;
			case HARVEST -> 0xFFE8C030;
			case OTHER -> 0xFFFFFFFF;
		};
	}

	/** The translucent fill colour of {@code kind}. */
	public static int fill(RtsTaskKind kind) {
		return withAlpha(line(kind), FILL_ALPHA);
	}

	/** {@code argb} with the same RGB and a lower alpha, for queued tasks that are not current yet. */
	public static int dimmed(int argb) {
		return withAlpha(argb, Math.min(argb >>> 24, DIMMED_ALPHA));
	}

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}
}
