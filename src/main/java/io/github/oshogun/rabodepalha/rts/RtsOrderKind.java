package io.github.oshogun.rabodepalha.rts;

import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/** What an RTS order asks the selected workers to do with the clicked block. */
public enum RtsOrderKind {
	MOVE,
	MINE,
	PLACE,
	/** Appended last so the wire ordinals of the others stay stable. */
	USE;

	private static final IntFunction<RtsOrderKind> BY_ID = ByIdMap.continuous(RtsOrderKind::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
	public static final StreamCodec<ByteBuf, RtsOrderKind> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, RtsOrderKind::ordinal);
}
