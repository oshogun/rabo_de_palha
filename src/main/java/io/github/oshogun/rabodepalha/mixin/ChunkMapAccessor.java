package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;

/** Exposes a level's player map, which vanilla keeps private, so tests can check whether a player loads chunks. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("playerMap")
	PlayerMap rabodepalha$getPlayerMap();
}
