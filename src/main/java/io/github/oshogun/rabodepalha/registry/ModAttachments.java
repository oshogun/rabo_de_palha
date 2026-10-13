package io.github.oshogun.rabodepalha.registry;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsEntryState;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public final class ModAttachments {
	/**
	 * Where a player entered RTS mode and what they were like then; present only while they are in it.
	 *
	 * <p>Persistent, so a player saved mid-RTS (logout, crash, restart) is restored on the next join.
	 * Not {@code copyOnDeath}: the respawned player must never carry RTS state over.
	 */
	public static final AttachmentType<RtsEntryState> RTS_ENTRY = AttachmentRegistry.createPersistent(
			RaboDePalha.id("rts_entry"),
			RtsEntryState.CODEC
	);

	private ModAttachments() {
	}

	/** Forces class loading (and so registration). */
	public static void init() {
	}
}
