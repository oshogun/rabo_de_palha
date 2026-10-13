package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.mixin.ChunkMapAccessor;
import io.github.oshogun.rabodepalha.registry.ModAttachments;
import io.github.oshogun.rabodepalha.rts.RtsEntryState;
import io.github.oshogun.rabodepalha.rts.RtsMode;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of RTS mode. The vanilla mock players fix their game mode and have no connection, so
 * these tests log in their own mock players (like the deprecated
 * {@code GameTestHelper.makeMockServerPlayerInLevel}, but with a real game mode) and log them out
 * again before succeeding.
 */
public class RtsModeGameTest {
	private static final String NO_SPECTATOR_CHUNKS = "rabodepalha-test:no_spectator_chunks";
	private static final AtomicInteger PLAYER_COUNTER = new AtomicInteger();
	private static final BlockPos START = new BlockPos(1, 2, 1);
	private static final BlockPos ELSEWHERE = new BlockPos(5, 4, 6);

	/** Logs in a non-op mock player in SURVIVAL, standing at {@link #START}. */
	static ServerPlayer joinPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		String name = "rts-" + PLAYER_COUNTER.incrementAndGet();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		moveTo(helper, player, START, 30.0F, 10.0F);
		return player;
	}

	private static void moveTo(GameTestHelper helper, ServerPlayer player, BlockPos rel, float yRot, float xRot) {
		Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(rel));
		player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), yRot, xRot, true);
	}

	static void leave(ServerPlayer player) {
		player.level().getServer().getPlayerList().remove(player);
	}

	private static ChunkMap chunkMap(GameTestHelper helper) {
		return helper.getLevel().getChunkSource().chunkMap;
	}

	/** Whether ChunkMap skips the player for chunk loading; unknown players count as skipped. */
	private static boolean ignoredByChunkMap(GameTestHelper helper, ServerPlayer player) {
		return ((ChunkMapAccessor) chunkMap(helper)).rabodepalha$getPlayerMap().ignoredOrUnknown(player);
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer player, Vec3 expected, float yRot, float xRot) {
		helper.assertTrue(player.level() == helper.getLevel(), "player in " + player.level().dimension());
		helper.assertTrue(player.position().distanceToSqr(expected) < 1.0E-6,
				"player at " + player.position() + ", expected " + expected);
		helper.assertTrue(Math.abs(player.getYRot() - yRot) < 1.0E-3F, "yRot " + player.getYRot() + ", expected " + yRot);
		helper.assertTrue(Math.abs(player.getXRot() - xRot) < 1.0E-3F, "xRot " + player.getXRot() + ", expected " + xRot);
	}

	private static void assertNormal(GameTestHelper helper, ServerPlayer player, GameType gameType) {
		helper.assertFalse(RtsMode.isActive(player), "player still in RTS mode");
		helper.assertFalse(player.hasAttached(ModAttachments.RTS_ENTRY), "player still has an RTS entry state");
		helper.assertTrue(player.gameMode() == gameType, "player in " + player.gameMode() + ", expected " + gameType);
	}

	/** Asserts that {@code player} is back in survival at {@link #START}, as {@link #joinPlayer} left them. */
	private static void assertRestoredSurvival(GameTestHelper helper, ServerPlayer player) {
		assertNormal(helper, player, GameType.SURVIVAL);
		assertAt(helper, player, Vec3.atBottomCenterOf(helper.absolutePos(START)), 30.0F, 10.0F);
		helper.assertFalse(player.getAbilities().flying, "survival player is flying");
		helper.assertFalse(player.getAbilities().mayfly, "survival player may fly");
		helper.assertFalse(player.getAbilities().invulnerable, "survival player is invulnerable");
		helper.assertTrue(player.getAbilities().mayBuild, "survival player may not build");
	}

	@GameTest
	public void enterSetsSpectatorAndActive(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		helper.assertTrue(RtsMode.enter(player), "enter refused");
		helper.assertTrue(RtsMode.isActive(player), "player not in RTS mode");
		helper.assertTrue(player.isSpectator(), "player in " + player.gameMode());
		RtsEntryState entry = player.getAttached(ModAttachments.RTS_ENTRY);
		helper.assertTrue(entry != null && entry.gameMode() == GameType.SURVIVAL, "entry state " + entry);
		helper.assertFalse(RtsMode.enter(player), "entered twice");
		RtsMode.exit(player);
		leave(player);
		helper.succeed();
	}

	@GameTest
	public void exitRestoresSurvival(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		RtsMode.enter(player);
		moveTo(helper, player, ELSEWHERE, 120.0F, 80.0F);
		helper.assertTrue(RtsMode.exit(player), "exit refused");
		assertRestoredSurvival(helper, player);
		helper.assertFalse(RtsMode.exit(player), "exited twice");
		leave(player);
		helper.succeed();
	}

	@GameTest
	public void exitRestoresCreativeFlying(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		player.setGameMode(GameType.CREATIVE);
		player.getAbilities().flying = true;
		player.getAbilities().setFlyingSpeed(0.07F);
		player.getAbilities().setWalkingSpeed(0.13F);
		player.onUpdateAbilities();
		Vec3 entryPos = player.position();

		RtsMode.enter(player);
		player.getAbilities().setFlyingSpeed(0.2F);
		player.getAbilities().setWalkingSpeed(0.05F);
		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);
		RtsMode.exit(player);

		assertNormal(helper, player, GameType.CREATIVE);
		assertAt(helper, player, entryPos, 30.0F, 10.0F);
		helper.assertTrue(player.getAbilities().flying, "creative player stopped flying");
		helper.assertTrue(player.getAbilities().mayfly, "creative player may not fly");
		helper.assertTrue(player.getAbilities().instabuild, "creative player lost instabuild");
		helper.assertTrue(player.getAbilities().getFlyingSpeed() == 0.07F, "fly speed " + player.getAbilities().getFlyingSpeed());
		helper.assertTrue(player.getAbilities().getWalkingSpeed() == 0.13F, "walk speed " + player.getAbilities().getWalkingSpeed());
		leave(player);
		helper.succeed();
	}

	/** Dying in RTS mode respawns the player in their entry state, not as a spectator at the spawn. */
	@GameTest
	public void respawnClearsRtsState(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		RtsMode.enter(player);
		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);

		ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
		helper.assertFalse(player.hasAttached(ModAttachments.RTS_ENTRY), "old player kept the RTS entry state");
		assertRestoredSurvival(helper, respawned);
		leave(respawned);
		helper.succeed();
	}

	/** A player saved mid-RTS (crash, restart) is restored when they join again. */
	@GameTest
	public void joinWithPersistedStateRestores(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		player.setAttached(ModAttachments.RTS_ENTRY, RtsEntryState.capture(player));
		player.setGameMode(GameType.SPECTATOR);
		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);

		ServerPlayerEvents.JOIN.invoker().onJoin(player);
		assertRestoredSurvival(helper, player);
		leave(player);
		helper.succeed();
	}

	/** Logging out restores the player before vanilla saves them. */
	@GameTest
	public void leaveRestoresBeforeSave(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		RtsMode.enter(player);
		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);

		leave(player);
		assertRestoredSurvival(helper, player);
		helper.succeed();
	}

	@GameTest
	public void entryStateCodecRoundTrip(GameTestHelper helper) {
		RtsEntryState state = new RtsEntryState(Level.NETHER, new Vec3(1.5, -60.25, 1.0E7), 123.5F, -45.0F,
				GameType.ADVENTURE, true, true, false, false, false, 0.07F, 0.13F);
		Tag tag = RtsEntryState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		RtsEntryState decoded = RtsEntryState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		helper.assertTrue(state.equals(decoded), "decoded " + decoded + ", expected " + state);
		helper.succeed();
	}

	@GameTest
	public void mobsIgnoreRtsPlayer(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, ELSEWHERE);
		helper.assertTrue(player.canBeSeenAsEnemy(), "survival player cannot be seen as an enemy");

		RtsMode.enter(player);
		helper.assertFalse(player.canBeSeenAsEnemy(), "RTS player can be seen as an enemy");
		helper.assertFalse(zombie.canAttack(player), "zombie can attack the RTS player");
		zombie.setTarget(player);
		helper.assertTrue(zombie.getTarget() == null, "zombie targets the RTS player");

		zombie.discard();
		RtsMode.exit(player);
		leave(player);
		helper.succeed();
	}

	/** Taking an RTS player out of spectator (e.g. {@code /gamemode}) ends RTS mode without moving them. */
	@GameTest
	public void gamemodeCommandDropsRts(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		RtsMode.enter(player);
		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);
		Vec3 pos = player.position();
		player.setGameMode(GameType.CREATIVE);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertFalse(RtsMode.isActive(player), "player still in RTS mode"))
				.thenExecute(() -> {
					assertNormal(helper, player, GameType.CREATIVE);
					assertAt(helper, player, pos, 0.0F, 90.0F);
					leave(player);
				})
				.thenSucceed();
	}

	/** The mock player is not on the op list, so its own permissions are level 0 as well. */
	@GameTest
	public void toggleRefusedWithoutPermission(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		helper.assertFalse(RtsMode.requestToggle(player, LevelBasedPermissionSet.forLevel(PermissionLevel.ALL)),
				"toggle allowed with level 0");
		assertRestoredSurvival(helper, player);
		helper.assertFalse(RtsMode.requestToggle(player), "toggle allowed for a non-op player");
		assertRestoredSurvival(helper, player);
		leave(player);
		helper.succeed();
	}

	@GameTest
	public void toggleAllowedWithGamemaster(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		helper.assertTrue(RtsMode.requestToggle(player, LevelBasedPermissionSet.GAMEMASTER), "toggle refused for a game master");
		helper.assertTrue(RtsMode.isActive(player), "player not in RTS mode");
		helper.assertTrue(player.isSpectator(), "player in " + player.gameMode());

		moveTo(helper, player, ELSEWHERE, 0.0F, 90.0F);
		helper.assertTrue(RtsMode.requestToggle(player, LevelBasedPermissionSet.forLevel(PermissionLevel.ALL)),
				"exit refused after losing permission");
		assertRestoredSurvival(helper, player);
		leave(player);
		helper.succeed();
	}

	@GameTest
	public void restorePathsIgnorePermission(GameTestHelper helper) {
		ServerPlayer joining = joinPlayer(helper);
		joining.setAttached(ModAttachments.RTS_ENTRY, RtsEntryState.capture(joining));
		joining.setGameMode(GameType.SPECTATOR);
		moveTo(helper, joining, ELSEWHERE, 0.0F, 90.0F);
		ServerPlayerEvents.JOIN.invoker().onJoin(joining);
		assertRestoredSurvival(helper, joining);
		leave(joining);

		ServerPlayer leaving = joinPlayer(helper);
		leaving.setAttached(ModAttachments.RTS_ENTRY, RtsEntryState.capture(leaving));
		leaving.setGameMode(GameType.SPECTATOR);
		moveTo(helper, leaving, ELSEWHERE, 0.0F, 90.0F);
		ServerPlayerEvents.LEAVE.invoker().onLeave(leaving);
		assertRestoredSurvival(helper, leaving);
		leave(leaving);
		helper.succeed();
	}

	@GameTest(environment = NO_SPECTATOR_CHUNKS)
	public void rtsPlayerNotIgnoredBySpectatorChunkRule(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		ChunkMap chunkMap = chunkMap(helper);
		helper.assertFalse(ignoredByChunkMap(helper, player), "survival player ignored");

		RtsMode.enter(player);
		chunkMap.move(player);
		helper.assertFalse(ignoredByChunkMap(helper, player), "RTS player ignored");
		RtsMode.exit(player);
		chunkMap.move(player);
		helper.assertFalse(ignoredByChunkMap(helper, player), "player ignored after leaving RTS mode");

		// Control: a plain spectator is ignored, so the game rule is in effect.
		player.setGameMode(GameType.SPECTATOR);
		chunkMap.move(player);
		helper.assertTrue(ignoredByChunkMap(helper, player), "plain spectator not ignored");
		player.setGameMode(GameType.SURVIVAL);
		chunkMap.move(player);
		helper.assertFalse(ignoredByChunkMap(helper, player), "player ignored after leaving spectator");
		leave(player);
		helper.succeed();
	}

	@GameTest(environment = NO_SPECTATOR_CHUNKS)
	public void rtsPlayerAddsDistanceTicket(GameTestHelper helper) {
		ServerPlayer player = joinPlayer(helper);
		ServerLevel level = helper.getLevel();
		ChunkMap chunkMap = chunkMap(helper);
		RtsMode.enter(player);

		Vec3 far = player.position().add(20 * 16, 0, 0);
		player.teleportTo(level, far.x, far.y, far.z, Set.of(), 0.0F, 90.0F, true);
		chunkMap.move(player);
		ChunkPos chunk = player.chunkPosition();
		helper.assertFalse(ignoredByChunkMap(helper, player), "RTS player ignored after panning");
		helper.assertTrue(chunkMap.getDistanceManager().hasPlayersNearby(chunk.pack()) == TriState.TRUE,
				"no player near " + chunk);

		RtsMode.exit(player);
		chunkMap.move(player);
		helper.assertFalse(ignoredByChunkMap(helper, player), "player ignored after leaving RTS mode");
		leave(player);
		helper.succeed();
	}
}
