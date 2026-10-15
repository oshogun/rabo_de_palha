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
		PayloadTypeRegistry.serverboundPlay().register(RtsPlantPayload.TYPE, RtsPlantPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsHarvestPayload.TYPE, RtsHarvestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsTendPayload.TYPE, RtsTendPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsWatchPayload.TYPE, RtsWatchPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsRemoveTaskPayload.TYPE, RtsRemoveTaskPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsDepositPayload.TYPE, RtsDepositPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsWithdrawPayload.TYPE, RtsWithdrawPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsContentsRequestPayload.TYPE,
				RtsContentsRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsBlueprintsRequestPayload.TYPE,
				RtsBlueprintsRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsBlueprintInfoRequestPayload.TYPE,
				RtsBlueprintInfoRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RtsBuildPayload.TYPE, RtsBuildPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsStatePayload.TYPE, RtsStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsOrderFeedbackPayload.TYPE,
				RtsOrderFeedbackPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsSnapshotPayload.TYPE, RtsSnapshotPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsContentsPayload.TYPE, RtsContentsPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsBlueprintsPayload.TYPE, RtsBlueprintsPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RtsBlueprintInfoPayload.TYPE,
				RtsBlueprintInfoPayload.STREAM_CODEC);

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
		ServerPlayNetworking.registerGlobalReceiver(RtsPlantPayload.TYPE,
				(payload, context) -> RtsOrders.handlePlant(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsHarvestPayload.TYPE,
				(payload, context) -> RtsOrders.handleHarvest(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsTendPayload.TYPE,
				(payload, context) -> RtsOrders.handleTend(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsWatchPayload.TYPE,
				(payload, context) -> RtsOrders.handleWatch(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsRemoveTaskPayload.TYPE,
				(payload, context) -> RtsOrders.handleRemoveTask(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsDepositPayload.TYPE,
				(payload, context) -> RtsOrders.handleDeposit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsWithdrawPayload.TYPE,
				(payload, context) -> RtsOrders.handleWithdraw(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsContentsRequestPayload.TYPE,
				(payload, context) -> RtsOrders.handleContents(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsBlueprintsRequestPayload.TYPE,
				(payload, context) -> RtsOrders.handleBlueprints(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsBlueprintInfoRequestPayload.TYPE,
				(payload, context) -> RtsOrders.handleBlueprintInfo(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(RtsBuildPayload.TYPE,
				(payload, context) -> RtsOrders.handleBuild(context.player(), payload));
	}
}
