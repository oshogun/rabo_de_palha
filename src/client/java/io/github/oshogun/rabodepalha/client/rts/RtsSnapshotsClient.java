package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsWatchPayload;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;

/**
 * Client cache of the snapshots the server sends for the watched workers. The watch follows the RTS selection:
 * {@link #syncWatch} sends the first {@link RtsWatchPayload#MAX_WATCHED} selected ids whenever they change, and
 * the server answers with {@link RtsSnapshotPayload}s while RTS mode lasts.
 */
public final class RtsSnapshotsClient {
	private static final Map<Integer, WorkerSnapshot> CACHE = new HashMap<>();
	/** The ids last sent in a watch, in selection order. */
	private static List<Integer> lastWatched = List.of();

	private RtsSnapshotsClient() {
	}

	/** Stores fresh snapshots and drops the workers the server reports as gone. */
	public static void accept(RtsSnapshotPayload payload) {
		for (WorkerSnapshot snapshot : payload.snapshots()) {
			CACHE.put(snapshot.entityId(), snapshot);
		}
		for (int id : payload.gone()) {
			CACHE.remove(id);
		}
	}

	/** The latest snapshot of worker {@code id}, or {@code null} if none has arrived yet. */
	public static @Nullable WorkerSnapshot get(int id) {
		return CACHE.get(id);
	}

	/** The watched ids, in selection order. */
	public static List<Integer> watched() {
		return lastWatched;
	}

	/**
	 * Sends a watch when the selected ids changed since the last one, and forgets snapshots of workers that are
	 * no longer selected. Runs every client tick, after the selection has been pruned.
	 */
	public static void syncWatch() {
		if (!RtsClient.isActive()) {
			return;
		}
		List<Integer> selected = RtsSelectionClient.selectedIds();
		List<Integer> ids = List.copyOf(selected.subList(0, Math.min(selected.size(), RtsWatchPayload.MAX_WATCHED)));
		if (ids.equals(lastWatched)) {
			return;
		}
		if (!ClientPlayNetworking.canSend(RtsWatchPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new RtsWatchPayload(ids));
		lastWatched = ids;
		Set<Integer> keep = new HashSet<>(ids);
		for (Integer id : new ArrayList<>(CACHE.keySet())) {
			if (!keep.contains(id)) {
				CACHE.remove(id);
			}
		}
	}

	/** Forgets every snapshot and the last watch; the server drops its side when RTS mode ends. */
	public static void clear() {
		CACHE.clear();
		lastWatched = List.of();
	}
}
