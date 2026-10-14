package io.github.oshogun.rabodepalha.task;

import java.util.Objects;

/** Result of one {@link Task#tick}. Every outcome except {@link Running} is terminal. */
public sealed interface TaskOutcome {
	Running RUNNING = new Running();

	static Succeeded succeeded(String note) {
		return new Succeeded(note);
	}

	static Failed failed(FailureReason reason, String detail) {
		return new Failed(reason, detail);
	}

	static Cancelled cancelled(Cancelled.Cause cause) {
		return new Cancelled(cause);
	}

	default boolean isTerminal() {
		return !(this instanceof Running);
	}

	record Running() implements TaskOutcome {
	}

	record Succeeded(String note) implements TaskOutcome {
		public Succeeded {
			note = note == null ? "" : note;
		}
	}

	record Failed(FailureReason reason, String detail) implements TaskOutcome {
		public Failed {
			Objects.requireNonNull(reason, "reason");
			detail = detail == null ? "" : detail;
		}
	}

	record Cancelled(Cause cause) implements TaskOutcome {
		public Cancelled {
			Objects.requireNonNull(cause, "cause");
		}

		/** Why a task was cancelled. */
		public enum Cause {
			/** A new order replaced the queue. */
			SUPERSEDED,
			/** The queue was cleared on request, e.g. {@code /rdp clear}. */
			CLEARED,
			/** The worker died or was discarded. */
			REMOVED;

			/** True if the cancelling player already knows about it, so it needs no report. */
			public boolean commanderInitiated() {
				return this != REMOVED;
			}
		}
	}
}
