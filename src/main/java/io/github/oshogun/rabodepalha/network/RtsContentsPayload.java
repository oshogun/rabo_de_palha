package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.worker.task.Containers;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a read-only snapshot of the container at {@code pos}, as (item id, total) entries. Totals
 * may exceed an item's max stack size, so they travel as plain counts rather than item stacks.
 */
public record RtsContentsPayload(BlockPos pos, List<RtsItemPicker.Entry> entries) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsContentsPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_contents"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsContentsPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RtsContentsPayload::pos,
			RtsItemPicker.Entry.STREAM_CODEC.apply(ByteBufCodecs.list(Containers.MAX_CONTENTS)), RtsContentsPayload::entries,
			RtsContentsPayload::new);

	public RtsContentsPayload {
		entries = List.copyOf(entries);
	}

	@Override
	public CustomPacketPayload.Type<RtsContentsPayload> type() {
		return TYPE;
	}
}
