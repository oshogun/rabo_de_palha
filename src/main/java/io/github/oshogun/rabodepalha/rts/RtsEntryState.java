package io.github.oshogun.rabodepalha.rts;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a player looked like when they entered RTS mode: where they stood, their game mode and their
 * abilities. Leaving RTS mode (or any restore path after logout, death or a restart) puts all of it
 * back exactly.
 */
public record RtsEntryState(
		ResourceKey<Level> dimension,
		Vec3 pos,
		float yRot,
		float xRot,
		GameType gameMode,
		boolean flying,
		boolean mayfly,
		boolean invulnerable,
		boolean instabuild,
		boolean mayBuild,
		float flySpeed,
		float walkSpeed) {
	public static final Codec<RtsEntryState> CODEC = RecordCodecBuilder.create(i -> i.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(RtsEntryState::dimension),
			Vec3.CODEC.fieldOf("pos").forGetter(RtsEntryState::pos),
			Codec.FLOAT.fieldOf("yRot").forGetter(RtsEntryState::yRot),
			Codec.FLOAT.fieldOf("xRot").forGetter(RtsEntryState::xRot),
			GameType.CODEC.fieldOf("gameMode").forGetter(RtsEntryState::gameMode),
			Codec.BOOL.fieldOf("flying").forGetter(RtsEntryState::flying),
			Codec.BOOL.fieldOf("mayfly").forGetter(RtsEntryState::mayfly),
			Codec.BOOL.fieldOf("invulnerable").forGetter(RtsEntryState::invulnerable),
			Codec.BOOL.fieldOf("instabuild").forGetter(RtsEntryState::instabuild),
			Codec.BOOL.fieldOf("mayBuild").forGetter(RtsEntryState::mayBuild),
			Codec.FLOAT.fieldOf("flySpeed").forGetter(RtsEntryState::flySpeed),
			Codec.FLOAT.fieldOf("walkSpeed").forGetter(RtsEntryState::walkSpeed)
	).apply(i, RtsEntryState::new));

	public static RtsEntryState capture(ServerPlayer player) {
		Abilities abilities = player.getAbilities();
		return new RtsEntryState(
				player.level().dimension(),
				player.position(),
				player.getYRot(),
				player.getXRot(),
				player.gameMode(),
				abilities.flying,
				abilities.mayfly,
				abilities.invulnerable,
				abilities.instabuild,
				abilities.mayBuild,
				abilities.getFlyingSpeed(),
				abilities.getWalkingSpeed());
	}

	/** Copies the saved abilities onto {@code abilities}; the caller syncs them to the client. */
	public void applyAbilities(Abilities abilities) {
		abilities.flying = flying;
		abilities.mayfly = mayfly;
		abilities.invulnerable = invulnerable;
		abilities.instabuild = instabuild;
		abilities.mayBuild = mayBuild;
		abilities.setFlyingSpeed(flySpeed);
		abilities.setWalkingSpeed(walkSpeed);
	}
}
