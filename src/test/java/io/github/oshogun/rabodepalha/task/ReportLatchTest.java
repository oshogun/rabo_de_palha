package io.github.oshogun.rabodepalha.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReportLatchTest {
	@Test
	void sameKeyReportsOnce() {
		ReportLatch latch = new ReportLatch();
		assertTrue(latch.shouldReport("no_container"));
		assertFalse(latch.shouldReport("no_container"));
		assertFalse(latch.shouldReport("no_container"));
	}

	@Test
	void newKeyReports() {
		ReportLatch latch = new ReportLatch();
		assertTrue(latch.shouldReport("no_container"));
		assertTrue(latch.shouldReport("container_full"));
		assertFalse(latch.shouldReport("container_full"));
	}

	@Test
	void keyReportsAgainAfterResolve() {
		ReportLatch latch = new ReportLatch();
		assertTrue(latch.shouldReport("container_full"));
		latch.resolve("container_full");
		assertTrue(latch.shouldReport("container_full"));
	}

	@Test
	void resolvingAnotherKeyKeepsTheLatch() {
		ReportLatch latch = new ReportLatch();
		assertTrue(latch.shouldReport("container_full"));
		latch.resolve("no_container");
		assertFalse(latch.shouldReport("container_full"));
	}

	@Test
	void resetClears() {
		ReportLatch latch = new ReportLatch();
		assertTrue(latch.shouldReport("no_container"));
		latch.reset();
		assertTrue(latch.shouldReport("no_container"));
	}
}
