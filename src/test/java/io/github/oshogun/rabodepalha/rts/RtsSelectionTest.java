package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RtsSelectionTest {
	@Test
	void clickReplacesSelection() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.clickSelect(3, false);

		assertEquals(List.of(3), selection.ids());
	}

	@Test
	void clickOnEmptyClears() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.clickSelect(null, false);

		assertTrue(selection.isEmpty());
	}

	@Test
	void shiftClickTogglesOnThenOff() {
		RtsSelection selection = new RtsSelection();
		selection.replace(1);

		selection.clickSelect(2, true);
		assertEquals(List.of(1, 2), selection.ids());

		selection.clickSelect(2, true);
		assertEquals(List.of(1), selection.ids());
	}

	@Test
	void shiftClickOnEmptyKeepsSelection() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.clickSelect(null, true);

		assertEquals(List.of(1, 2), selection.ids());
	}

	@Test
	void boxReplacesSelection() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.boxSelect(List.of(3, 4), false);

		assertEquals(List.of(3, 4), selection.ids());
	}

	@Test
	void emptyBoxClearsSelection() {
		RtsSelection selection = new RtsSelection();
		selection.replace(1);

		selection.boxSelect(List.of(), false);

		assertTrue(selection.isEmpty());
	}

	@Test
	void shiftBoxAddsWithoutRemoving() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.boxSelect(List.of(2, 3), true);

		assertEquals(List.of(1, 2, 3), selection.ids());
	}

	@Test
	void clearEmpties() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2));

		selection.clear();

		assertEquals(0, selection.size());
		assertFalse(selection.contains(1));
	}

	@Test
	void retainIfPrunes() {
		RtsSelection selection = new RtsSelection();
		selection.replaceAll(List.of(1, 2, 3, 4));

		selection.retainIf(id -> id % 2 == 0);

		assertEquals(List.of(2, 4), selection.ids());
	}

	@Test
	void idsIsACopy() {
		RtsSelection selection = new RtsSelection();
		selection.replace(1);
		List<Integer> ids = selection.ids();

		selection.replace(2);

		assertEquals(List.of(1), ids);
	}
}
