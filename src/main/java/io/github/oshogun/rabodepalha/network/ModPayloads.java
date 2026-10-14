package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.rts.RtsMode;
import io.github.oshogun.rabodepalha.rts.RtsOrders;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ModPayloads {
	private ModPayloads() {
	}

	/** Registers the payload types on both sides and the server-side receivers. */
	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(RtsTogglePayload.TYPE, RtsTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsOrderPayload.TYPE, RtsOrderPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsStopPayload.TYPE, RtsStopPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsSpawnPayload.TYPE, RtsSpawnPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsDigPayload.TYPE, RtsDigPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsStatePayload.TYPE, RtsStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsOrderFeedbackPayload.TYPE,
				RtsOrderFeedbackPayload.STREAM_CODEC);

		// Play receivers run on the server thread, so RtsMode may touch the player directly.
		ServerPlayNetworking.registerGlobalReceiver(RtsTogglePayload.TYPE,
				(payload, context) -> RtsMode.requestToggle(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(RtsOrderPayload.TYPE,
				(payload, context) -> RtsOrders.handle(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsStopPayload.TYPE,
				(payload, context) -> RtsOrders.handleStop(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsSpawnPayload.TYPE,
				(payload, context) -> RtsOrders.handleSpawn(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsDigPayload.TYPE,
				(payload, context) -> RtsOrders.handleDig(context.player(), payload));
	}
}
