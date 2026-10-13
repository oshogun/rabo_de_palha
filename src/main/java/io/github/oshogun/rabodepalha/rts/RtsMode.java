package io.github.oshogun.rabodepalha.rts;

import java.util.Set;

import io.github.oshogun.rabodepalha.network.RtsStatePayload;
import io.github.oshogun.rabodepalha.registry.ModAttachments;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;

/**
 * Server side of RTS mode: the player entity becomes the camera.
 *
 * <p>Internally the player is put into vanilla {@link GameType#SPECTATOR}: that already makes them
 * invisible to others, ignored by mobs, invulnerable, gravity- and collision-free, and unable to pick
 * up items or touch the world. The state they entered with is kept in the persistent
 * {@link ModAttachments#RTS_ENTRY} attachment; its presence is what "in RTS mode" means. Every way out
 * (toggle, logout, death/respawn, rejoin after a restart) restores that state exactly.
 *
 * <p>Ordering matters for chunk loading (see {@code ChunkMapMixin}): the attachment is set before the
 * player becomes a spectator and removed only after they stopped being one, so ChunkMap never sees the
 * player as a skipped spectator in between.
 */
public final class RtsMode {
	private RtsMode() {
	}

	public static void init() {
		ServerPlayerEvents.JOIN.register(RtsMode::onJoin);
		ServerPlayerEvents.LEAVE.register(RtsMode::onLeave);
		ServerPlayerEvents.AFTER_RESPAWN.register(RtsMode::onRespawn);
		ServerTickEvents.END_SERVER_TICK.register(RtsMode::tickGuard);
	}

	public static boolean isActive(ServerPlayer player) {
		return player.hasAttached(ModAttachments.RTS_ENTRY);
	}

	/** Only game masters (permission level 2, like {@code /rdp}) may enter RTS mode. */
	static boolean canEnter(PermissionSet permissions) {
		return Commands.LEVEL_GAMEMASTERS.check(permissions);
	}

	/** Entry point for the client's toggle request. */
	public static boolean requestToggle(ServerPlayer player) {
		return requestToggle(player, player.permissions());
	}

	/**
	 * Leaves RTS mode if active (always allowed), otherwise enters it if {@code permissions} allow.
	 *
	 * @return whether the player's RTS state changed
	 */
	public static boolean requestToggle(ServerPlayer player, PermissionSet permissions) {
		if (isActive(player)) {
			return exit(player);
		}
		if (!canEnter(permissions)) {
			player.sendSystemMessage(Component.translatable("rabodepalha.rts.no_permission"));
			return false;
		}
		return enter(player);
	}

	/** Puts the player into RTS mode. No permission check; see {@link #requestToggle}. */
	public static boolean enter(ServerPlayer player) {
		if (isActive(player)) {
			return false;
		}
		// Attachment first, so the player is never a non-RTS spectator (skipped by ChunkMap).
		player.setAttached(ModAttachments.RTS_ENTRY, RtsEntryState.capture(player));
		if (!player.setGameMode(GameType.SPECTATOR) && !player.isSpectator()) {
			player.removeAttached(ModAttachments.RTS_ENTRY);
			return false;
		}
		sendState(player, true);
		return true;
	}

	/** Takes the player out of RTS mode, back to where and how they entered. No permission check. */
	public static boolean exit(ServerPlayer player) {
		RtsEntryState entry = player.getAttached(ModAttachments.RTS_ENTRY);
		if (entry == null) {
			return false;
		}
		restore(player, entry, true);
		// Removed only now that the player is no longer a spectator.
		player.removeAttached(ModAttachments.RTS_ENTRY);
		sendState(player, false);
		return true;
	}

	/**
	 * Applies {@code entry}: game mode, then abilities, then position.
	 *
	 * @param teleportAcrossDimensions whether a different entry dimension may be teleported to
	 * @return whether the player is now in the entry dimension at the entry position
	 */
	static boolean restore(ServerPlayer player, RtsEntryState entry, boolean teleportAcrossDimensions) {
		player.setGameMode(entry.gameMode());
		entry.applyAbilities(player.getAbilities());
		player.onUpdateAbilities();

		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.getLevel(entry.dimension());
		if (level == null) {
			// The entry dimension is gone; stay where we are rather than get stuck in RTS mode.
			return true;
		}
		if (level != player.level() && !teleportAcrossDimensions) {
			return false;
		}
		return player.teleportTo(level, entry.pos().x, entry.pos().y, entry.pos().z, Set.of(),
				entry.yRot(), entry.xRot(), true);
	}

	/**
	 * Restores before vanilla saves the leaving player, so they are stored in normal mode at their entry
	 * position. A cross-dimension teleport is not safe while disconnecting; then the attachment is kept
	 * and {@link #onJoin} finishes the job on the next login.
	 */
	static void onLeave(ServerPlayer player) {
		RtsEntryState entry = player.getAttached(ModAttachments.RTS_ENTRY);
		if (entry == null) {
			return;
		}
		if (restore(player, entry, false)) {
			player.removeAttached(ModAttachments.RTS_ENTRY);
		}
	}

	/** A player saved while in RTS mode (crash, restart, cross-dimension logout) comes back restored. */
	static void onJoin(ServerPlayer player) {
		RtsEntryState entry = player.getAttached(ModAttachments.RTS_ENTRY);
		if (entry == null) {
			return;
		}
		restore(player, entry, true);
		player.removeAttached(ModAttachments.RTS_ENTRY);
		sendState(player, false);
	}

	/**
	 * The new player copies the old one's game mode but not the attachment, so a player who died (or
	 * otherwise respawned) in RTS mode is restored from the old player's entry state.
	 */
	static void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		RtsEntryState entry = oldPlayer.getAttached(ModAttachments.RTS_ENTRY);
		if (entry == null) {
			entry = newPlayer.getAttached(ModAttachments.RTS_ENTRY);
		}
		oldPlayer.removeAttached(ModAttachments.RTS_ENTRY);
		if (entry == null) {
			return;
		}
		// The event fires inside PlayerList.respawn, before vanilla points the shared connection at the
		// new player; without this the teleport would move the old player and the new one would stay at
		// the respawn point. Vanilla assigns the same value right after.
		newPlayer.connection.player = newPlayer;
		restore(newPlayer, entry, true);
		newPlayer.removeAttached(ModAttachments.RTS_ENTRY);
		sendState(newPlayer, false);
	}

	/**
	 * If something else (e.g. {@code /gamemode}) took an RTS player out of spectator, RTS mode is over:
	 * drop the state without moving them, since the new game mode was chosen deliberately.
	 */
	private static void tickGuard(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isActive(player) && !player.isSpectator()) {
				player.removeAttached(ModAttachments.RTS_ENTRY);
				sendState(player, false);
			}
		}
	}

	private static void sendState(ServerPlayer player, boolean active) {
		if (ServerPlayNetworking.canSend(player, RtsStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, new RtsStatePayload(active));
		}
	}
}
