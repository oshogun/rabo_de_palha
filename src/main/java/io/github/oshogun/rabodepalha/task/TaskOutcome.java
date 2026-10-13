package io.github.oshogun.rabodepalha.task;

import java.util.Objects;

/** Result of one {@link Task#tick}. Every outcome except {@link Running} is terminal. */
public sealed interface TaskOutcome {
	Running RUNNING = new Running();
	Cancelled CANCELLED = new Cancelled();

	static Succeeded succeeded(String note) {
		return new Succeeded(note);
	}

	static Failed failed(FailureReason reason, String detail) {
		return new Failed(reason, detail);
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

	record Cancelled() implements TaskOutcome {
	}
}
