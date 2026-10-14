package io.github.oshogun.rabodepalha.client.rts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;

import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsPayload;
import io.github.oshogun.rabodepalha.rts.BlueprintCatalog;
import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.rts.RtsBlueprintPicker;
import io.github.oshogun.rabodepalha.rts.RtsCommandBar;

/**
 * Client state of the RTS build command: the blueprint list for the picker, the armed blueprint and its
 * rotation, and the per-blueprint infos (size and materials). The list holds ids only; an info is asked for
 * once per id when a blueprint is picked and cached for the RTS session, so a template re-saved mid-session
 * keeps its old size until RTS is toggled off and on.
 */
public final class RtsBlueprintsClient {
	/** One blueprint's unrotated size and the items it needs, as the server sent them. */
	public record Info(Identifier id, Vec3i size, List<RtsBlueprintInfoPayload.Material> materials) {
		public Info {
			materials = List.copyOf(materials);
		}

		/** The materials as an item to count map, most needed first. */
		public Map<Item, Integer> needed() {
			Map<Item, Integer> needed = new LinkedHashMap<>();
			for (RtsBlueprintInfoPayload.Material material : materials) {
				needed.merge(material.item(), material.count(), Integer::sum);
			}
			return needed;
		}
	}

	private static @Nullable List<BlueprintCatalog.Entry> listing;
	private static int more;
	private static boolean includeVanilla;
	private static boolean pickerOpen;
	private static int hoveredRow = -1;
	private static int scroll;
	private static Optional<Identifier> armed = Optional.empty();
	private static Rotation rotation = Rotation.NONE;
	private static final Map<Identifier, Info> infos = new HashMap<>();
	private static final Set<Identifier> pending = new HashSet<>();

	private RtsBlueprintsClient() {
	}

	/** Opens the picker; the caller asks for a fresh list, and the old one stays shown until the reply arrives. */
	public static void open() {
		pickerOpen = true;
		hoveredRow = -1;
		scroll = 0;
	}

	/** Stores a list reply, unless it answers a request with the other vanilla setting. */
	public static void accept(RtsBlueprintsPayload payload) {
		if (payload.includeVanilla() != includeVanilla) {
			return;
		}
		listing = payload.entries();
		more = payload.more();
		hoveredRow = -1;
		scroll = 0;
	}

	/** Caches an info reply. */
	public static void acceptInfo(RtsBlueprintInfoPayload payload) {
		pending.remove(payload.id());
		infos.put(payload.id(), new Info(payload.id(), payload.size(), payload.materials()));
	}

	/**
	 * Forgets the pending info requests after an unknown_blueprint rejection; the feedback names no id, so a
	 * later pick may ask again.
	 */
	public static void clearPending() {
		pending.clear();
	}

	/** Shows or hides vanilla templates and asks for the list again. */
	public static void toggleVanilla() {
		includeVanilla = !includeVanilla;
		listing = null;
		more = 0;
		hoveredRow = -1;
		scroll = 0;
		RtsOrdersClient.requestBlueprints(includeVanilla);
	}

	/** Arms {@code id}, closes the picker and asks for its info unless it is cached or already asked for. */
	public static void pick(Identifier id) {
		armed = Optional.of(id);
		pickerOpen = false;
		requestInfo(id);
	}

	/** Asks the server for {@code id}'s info once per session. */
	public static void requestInfo(Identifier id) {
		if (infos.containsKey(id) || !pending.add(id)) {
			return;
		}
		RtsOrdersClient.requestBlueprintInfo(id);
	}

	/** The armed blueprint's info, or null when none is armed or its info has not arrived. */
	public static @Nullable Info armedInfo() {
		return armed.map(infos::get).orElse(null);
	}

	/** Turns the armed blueprint by 90 degrees clockwise. */
	public static void cycleRotation() {
		rotation = BlueprintFootprint.next(rotation);
	}

	/** Closes the picker and disarms; the list and the infos stay for the session. */
	public static void clear() {
		pickerOpen = false;
		hoveredRow = -1;
		scroll = 0;
		armed = Optional.empty();
		rotation = Rotation.NONE;
	}

	/** Drops everything, including the list and the cached infos; on entering or leaving RTS mode. */
	public static void resetSession() {
		clear();
		listing = null;
		more = 0;
		includeVanilla = false;
		infos.clear();
		pending.clear();
	}

	/** Closes the picker, keeping the armed blueprint. */
	public static void closePicker() {
		pickerOpen = false;
		hoveredRow = -1;
	}

	/** The listed blueprints, or null until the reply arrives. */
	public static @Nullable List<BlueprintCatalog.Entry> listing() {
		return listing;
	}

	/** How many blueprints the server left out of the list. */
	public static int more() {
		return more;
	}

	public static boolean includeVanilla() {
		return includeVanilla;
	}

	public static boolean pickerOpen() {
		return pickerOpen;
	}

	public static int hoveredRow() {
		return hoveredRow;
	}

	public static void setHoveredRow(int row) {
		hoveredRow = row;
	}

	public static int scroll() {
		return scroll;
	}

	public static void setScroll(int value) {
		scroll = Math.max(0, value);
	}

	public static Optional<Identifier> armed() {
		return armed;
	}

	public static boolean isArmed() {
		return armed.isPresent();
	}

	public static Rotation rotation() {
		return rotation;
	}

	/** Whether the picker is on screen: open while the build command is armed. */
	public static boolean pickerShown() {
		return pickerOpen && RtsCommandClient.buildArmed();
	}

	/** The blueprints the picker lists, in the server's order; vanilla ones only with the toggle on. */
	public static List<BlueprintCatalog.Entry> shownEntries() {
		List<BlueprintCatalog.Entry> entries = listing;
		if (entries == null) {
			return List.of();
		}
		return entries.stream()
				.filter(entry -> includeVanilla || entry.source() != BlueprintCatalog.Source.VANILLA)
				.toList();
	}

	/** The number of list rows above the toggle: one loading row, or the entries plus a "+N more" row. */
	public static int rowCount() {
		if (listing == null) {
			return 1;
		}
		return shownEntries().size() + (more > 0 ? 1 : 0);
	}

	/** The picker's layout on the current window, or null when it is not shown. */
	public static RtsBlueprintPicker.@Nullable Layout layout() {
		if (!pickerShown()) {
			return null;
		}
		Window window = Minecraft.getInstance().getWindow();
		return RtsBlueprintPicker.layout(window.getGuiScaledWidth(), window.getGuiScaledHeight(), rowCount(), scroll);
	}

	/** What the window pixel ({@code x}, {@code y}) falls on in the picker. */
	public static RtsBlueprintPicker.Hit hitAt(double x, double y) {
		RtsBlueprintPicker.Layout layout = layout();
		if (layout == null) {
			return RtsBlueprintPicker.Hit.NONE;
		}
		double scale = Minecraft.getInstance().getWindow().getGuiScale();
		return RtsBlueprintPicker.hitTest(layout, RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/** Whether the window pixel ({@code x}, {@code y}) is on the picker. */
	public static boolean over(double x, double y) {
		return !(hitAt(x, y) instanceof RtsBlueprintPicker.Hit.None);
	}

	/** Handles a left press. Returns true when it fell on the picker, which consumes it. */
	public static boolean onLeftPress(double x, double y) {
		switch (hitAt(x, y)) {
			case RtsBlueprintPicker.Hit.None none -> {
				return false;
			}
			case RtsBlueprintPicker.Hit.Row row -> {
				List<BlueprintCatalog.Entry> entries = shownEntries();
				// The loading and "+N more" rows are not clickable.
				if (listing != null && row.index() < entries.size()) {
					pick(entries.get(row.index()).id());
				}
			}
			case RtsBlueprintPicker.Hit.Toggle toggle -> toggleVanilla();
			case RtsBlueprintPicker.Hit.Body body -> {
				// Consumed: a press on the picker's background must not select or order anything.
			}
		}
		return true;
	}

	/**
	 * Handles the scroll wheel over the picker: wheel up shows earlier rows. Returns true when the cursor is on
	 * the picker, which consumes the scroll.
	 */
	public static boolean onScroll(double x, double y, double yoffset) {
		RtsBlueprintPicker.Layout layout = layout();
		if (layout == null || !over(x, y)) {
			return false;
		}
		scroll = Math.max(0, Math.min(layout.first() - (int) Math.signum(yoffset), layout.maxScroll()));
		return true;
	}

	/** Handles Esc. Returns true if it closed the picker, which consumes the key; the build command stays armed. */
	public static boolean onEscape() {
		if (!pickerShown()) {
			return false;
		}
		closePicker();
		return true;
	}
}
