package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsWatchPayload;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which workers each RTS player's panel is watching, and what was last sent about them. Every
 * {@link #INTERVAL} ticks the server sends an {@link RtsSnapshotPayload} with the workers whose snapshot
 * changed and the ids that are gone. A watch ends when the player leaves RTS mode or the server, or when
 * every watched worker is gone.
 */
public final class RtsWatchers {
	public static final int MAX_WATCHED = RtsWatchPayload.MAX_WATCHED;
	/** Ticks between change checks. */
	public static final int INTERVAL = 5;

	private static final Map<UUID, Watch> WATCHES = new HashMap<>();

	private RtsWatchers() {
	}

	private record Watch(List<Integer> ids, Map<Integer, WorkerSnapshot> lastSent, Set<Integer> pendingGone) {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(RtsWatchers::tick);
		ServerPlayerEvents.LEAVE.register(player -> WATCHES.remove(player.getUUID()));
	}

	/**
	 * Replaces the player's watch with {@code liveIds}; {@code goneIds} are reported as gone on the next
	 * collect. Nothing is kept when both are empty.
	 */
	public static void set(ServerPlayer player, List<Integer> liveIds, List<Integer> goneIds) {
		if (liveIds.isEmpty() && goneIds.isEmpty()) {
			WATCHES.remove(player.getUUID());
			return;
		}
		WATCHES.put(player.getUUID(), new Watch(new ArrayList<>(liveIds), new HashMap<>(), new LinkedHashSet<>(goneIds)));
	}

	/** The ids the player is watching, in watch order. */
	public static List<Integer> watched(ServerPlayer player) {
		Watch watch = WATCHES.get(player.getUUID());
		return watch == null ? List.of() : List.copyOf(watch.ids());
	}

	/** Forces the next collect to resend {@code workerId}'s snapshot to the player, if they watch it. */
	public static void markDirty(ServerPlayer player, int workerId) {
		Watch watch = WATCHES.get(player.getUUID());
		if (watch != null) {
			watch.lastSent().remove(workerId);
		}
	}

	/**
	 * The snapshots that changed since the last collect and the ids that are gone, or empty when there is
	 * nothing to send. Workers that are missing, dead or not workers stop being watched; a watch with no ids
	 * left is dropped.
	 */
	public static Optional<RtsSnapshotPayload> collect(ServerPlayer player) {
		Watch watch = WATCHES.get(player.getUUID());
		if (watch == null) {
			return Optional.empty();
		}
		List<WorkerSnapshot> snapshots = new ArrayList<>();
		List<Integer> gone = new ArrayList<>(watch.pendingGone());
		watch.pendingGone().clear();
		for (Iterator<Integer> it = watch.ids().iterator(); it.hasNext();) {
			int id = it.next();
			if (!(player.level().getEntity(id) instanceof WorkerEntity worker) || !worker.isAlive()) {
				it.remove();
				watch.lastSent().remove(id);
				gone.add(id);
				continue;
			}
			WorkerSnapshot snapshot = WorkerSnapshot.of(worker);
			WorkerSnapshot last = watch.lastSent().get(id);
			if (last == null || !WorkerSnapshot.matches(last, snapshot)) {
				watch.lastSent().put(id, snapshot);
				snapshots.add(snapshot);
			}
		}
		if (watch.ids().isEmpty()) {
			WATCHES.remove(player.getUUID());
		}
		if (snapshots.isEmpty() && gone.isEmpty()) {
			return Optional.empty();
		}
		// The payload codec caps both lists; ids are capped on watch, so this only guards odd pendingGone sizes.
		if (gone.size() > MAX_WATCHED) {
			gone = gone.subList(0, MAX_WATCHED);
		}
		return Optional.of(new RtsSnapshotPayload(snapshots, gone));
	}

	private static void tick(MinecraftServer server) {
		if (WATCHES.isEmpty() || server.getTickCount() % INTERVAL != 0) {
			return;
		}
		for (Iterator<UUID> it = WATCHES.keySet().iterator(); it.hasNext();) {
			ServerPlayer player = server.getPlayerList().getPlayer(it.next());
			if (player == null || !RtsMode.isActive(player)) {
				it.remove();
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!WATCHES.containsKey(player.getUUID())) {
				continue;
			}
			Optional<RtsSnapshotPayload> payload = collect(player);
			if (payload.isPresent() && ServerPlayNetworking.canSend(player, RtsSnapshotPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload.get());
			}
		}
	}
}
