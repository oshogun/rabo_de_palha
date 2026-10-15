package io.github.oshogun.rabodepalha.task;

import java.util.Objects;

/**
 * Reports each problem of a repeating order once instead of every round. A key is reported when it differs from
 * the last reported key, and again only after {@link #resolve} or {@link #reset} clears it.
 *
 * <p>Runtime-only and never saved: after a reload a problem that is still there is reported once more.
 */
public final class ReportLatch {
	private String lastReported;

	/** True if {@code key} should be reported now; records it as reported. */
	public boolean shouldReport(String key) {
		Objects.requireNonNull(key, "key");
		if (key.equals(lastReported)) {
			return false;
		}
		lastReported = key;
		return true;
	}

	/** Clears {@code key} once its condition is gone, so it is reported again if it comes back. */
	public void resolve(String key) {
		if (Objects.equals(key, lastReported)) {
			lastReported = null;
		}
	}

	/** Clears whatever was reported last. */
	public void reset() {
		lastReported = null;
	}
}
