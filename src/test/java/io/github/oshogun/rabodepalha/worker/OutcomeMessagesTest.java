package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;

import org.junit.jupiter.api.Test;

class OutcomeMessagesTest {
	@Test
	void commanderInitiatedCancellationsAreNotReported() {
		assertFalse(OutcomeMessages.notifiesCommanders(TaskOutcome.cancelled(Cause.SUPERSEDED)));
		assertFalse(OutcomeMessages.notifiesCommanders(TaskOutcome.cancelled(Cause.CLEARED)));
	}

	@Test
	void otherOutcomesAreReported() {
		assertTrue(OutcomeMessages.notifiesCommanders(TaskOutcome.cancelled(Cause.REMOVED)));
		assertTrue(OutcomeMessages.notifiesCommanders(TaskOutcome.succeeded("")));
		assertTrue(OutcomeMessages.notifiesCommanders(TaskOutcome.failed(FailureReason.ERROR, "boom")));
	}

	@Test
	void describesCancellationCause() {
		assertEquals("cancelled (worker removed)", OutcomeMessages.describe(TaskOutcome.cancelled(Cause.REMOVED)));
		assertEquals("cancelled", OutcomeMessages.describe(TaskOutcome.cancelled(Cause.SUPERSEDED)));
		assertEquals("cancelled", OutcomeMessages.describe(TaskOutcome.cancelled(Cause.CLEARED)));
	}

	@Test
	void describesOtherOutcomes() {
		assertEquals("done", OutcomeMessages.describe(TaskOutcome.succeeded("")));
		assertEquals("done (ok)", OutcomeMessages.describe(TaskOutcome.succeeded("ok")));
		assertEquals("failed: ERROR (boom)", OutcomeMessages.describe(TaskOutcome.failed(FailureReason.ERROR, "boom")));
	}
}
