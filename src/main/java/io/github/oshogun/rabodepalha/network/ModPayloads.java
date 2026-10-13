package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.rts.RtsMode;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ModPayloads {
	private ModPayloads() {
	}

	/** Registers the payload types on both sides and the server-side receivers. */
	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(RtsTogglePayload.TYPE, RtsTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsStatePayload.TYPE, RtsStatePayload.STREAM_CODEC);

		// Play receivers run on the server thread, so RtsMode may touch the player directly.
		ServerPlayNetworking.registerGlobalReceiver(RtsTogglePayload.TYPE,
				(payload, context) -> RtsMode.requestToggle(context.player()));
	}
}
