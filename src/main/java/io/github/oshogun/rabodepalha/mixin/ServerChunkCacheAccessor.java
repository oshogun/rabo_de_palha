package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;

/** Exposes a level's ticket storage, which vanilla keeps private, so tests can check which tickets a chunk holds. */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
	@Accessor("ticketStorage")
	TicketStorage rabodepalha$getTicketStorage();
}
