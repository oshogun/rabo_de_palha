package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import io.github.oshogun.rabodepalha.worker.task.TaskSummary;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RtsTaskKindTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyTaskKeyMapsToItsKind() {
		Map<String, RtsTaskKind> expected = Map.of(
				"move_to", RtsTaskKind.MOVE,
				"mine", RtsTaskKind.MINE,
				"place", RtsTaskKind.PLACE,
				"dig_area", RtsTaskKind.DIG,
				"build", RtsTaskKind.BUILD,
				"deposit", RtsTaskKind.DEPOSIT,
				"deposit_item", RtsTaskKind.DEPOSIT,
				"withdraw", RtsTaskKind.WITHDRAW,
				"unknown", RtsTaskKind.OTHER);
		expected.forEach((suffix, kind) -> assertEquals(kind, RtsTaskKind.of(summary("rabodepalha.task." + suffix)), suffix));
	}

	@Test
	void malformedKeysAreOther() {
		for (String key : List.of("", "move_to", "rabodepalha.task.", "rabodepalha.task.MINE", "rabodepalha.task.mine.extra",
				"other.task.mine", "rabodepalha.tasks.mine")) {
			assertEquals(RtsTaskKind.OTHER, RtsTaskKind.of(summary(key)), key);
		}
	}

	@Test
	void orderKindsMap() {
		assertEquals(RtsTaskKind.MOVE, RtsTaskKind.from(RtsOrderKind.MOVE));
		assertEquals(RtsTaskKind.MINE, RtsTaskKind.from(RtsOrderKind.MINE));
		assertEquals(RtsTaskKind.PLACE, RtsTaskKind.from(RtsOrderKind.PLACE));
	}

	private static TaskSummary summary(String key) {
		return new TaskSummary(key, List.of(), List.of());
	}
}
