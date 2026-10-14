package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;
import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsPayload;
import io.github.oshogun.rabodepalha.network.RtsContentsPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderFeedbackPayload;
import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsStatePayload;
import io.github.oshogun.rabodepalha.network.RtsTogglePayload;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;
import io.github.oshogun.rabodepalha.rts.RtsCameraMath;
import io.github.oshogun.rabodepalha.rts.RtsViewLevel;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

/**
 * Client half of RTS mode. The server owns the state: the toggle key only asks, and the camera switches
 * on when an {@link RtsStatePayload} says so. While active, the local player is the camera: each client
 * tick it is panned, kept at a fixed height above the ground and pointed down at an angle.
 */
public final class RtsClient {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(RaboDePalha.id("rts"));

	public static KeyMapping toggleKey;
	public static KeyMapping rotateLeftKey;
	public static KeyMapping rotateRightKey;
	public static KeyMapping tiltUpKey;
	public static KeyMapping tiltDownKey;
	/** Hotkeys of the command bar, in the order of {@link RtsCommands#COMMANDS}. */
	public static List<KeyMapping> commandKeys = List.of();

	/** Toggles freezing the camera height while panning. */
	public static KeyMapping lockHeightKey;
	/** Turns the armed blueprint by 90 degrees; not Q/E, which turn the camera. */
	public static KeyMapping rotateBlueprintKey;
	/** Step the view level up and down one block; held, they repeat. */
	public static KeyMapping levelUpKey;
	public static KeyMapping levelDownKey;
	/** Switches between the surface view and the view level. */
	public static KeyMapping levelModeKey;
	/** Moves the view level to the feet of the first selected worker. */
	public static KeyMapping levelToWorkerKey;

	/** Time step assumed for the first tick, when there is no previous tick to measure from. */
	private static final double DEFAULT_TICK_DT = 0.05;

	private static boolean active;
	private static double height = RtsCameraMath.DEFAULT_HEIGHT;
	private static float yaw;
	private static float pitch = RtsCameraMath.DEFAULT_PITCH;
	/** Orbit displacement from rotating, tilting and zooming since the last tick, applied with the pan. */
	private static double pendingDX;
	private static double pendingDZ;
	/** Smoothed camera height; keeps the last ground reference while the chunks below are not loaded. */
	private static RtsCameraMath.HeightState heightState = RtsCameraMath.HeightState.UNSET;
	private static long lastTickNanos;
	private static boolean heightLocked;
	/** Camera pose applied on the previous tick, used as the start of the per-frame interpolation. */
	private static double lastX = Double.NaN;
	private static double lastY;
	private static double lastZ;
	private static float lastYaw;
	/** How the camera finds the ground; in {@link RtsViewLevel.Mode#LEVEL} it treats {@link #viewLevel} as the top of the world. */
	private static RtsViewLevel.Mode viewMode = RtsViewLevel.Mode.SURFACE;
	private static int viewLevel;
	/** Blocks the view level lies below the surface at the screen centre; NaN in the surface view or when unknown. */
	private static double centreDepth = Double.NaN;
	/** A deliberate level or mode change is waiting to rebase the height filter onto the new reference. */
	private static boolean levelDirty;
	/** Ticks each level step key has been held, or -1 when it is up. */
	private static int levelUpHeld = -1;
	private static int levelDownHeld = -1;

	private RtsClient() {
	}

	public static void init() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_toggle",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY));
		rotateLeftKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_rotate_left",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_Q, CATEGORY));
		rotateRightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_rotate_right",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_E, CATEGORY));
		tiltUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_tilt_up",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_X, CATEGORY));
		tiltDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_tilt_down",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY));
		lockHeightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_lock_height",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY));
		rotateBlueprintKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_rotate_blueprint",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_U, CATEGORY));
		commandKeys = RtsCommands.COMMANDS.stream()
				.map(command -> KeyMappingHelper.registerKeyMapping(new KeyMapping(command.keyMappingName(),
						InputConstants.Type.KEYBOARD, command.defaultKey(), CATEGORY)))
				.toList();
		levelUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_level_up",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_PAGEUP, CATEGORY));
		levelDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_level_down",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_PAGEDOWN, CATEGORY));
		levelModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_level_mode",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_END, CATEGORY));
		levelToWorkerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_level_to_worker",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_HOME, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(RtsStatePayload.TYPE,
				(payload, context) -> setActive(context.client(), payload.active()));
		ClientPlayNetworking.registerGlobalReceiver(RtsOrderFeedbackPayload.TYPE,
				(payload, context) -> {
					if (isUnknownBlueprint(payload.message())) {
						RtsBlueprintsClient.clearPending();
					}
					RtsHud.showFeedback(payload.ok(), payload.message());
				});
		ClientPlayNetworking.registerGlobalReceiver(RtsSnapshotPayload.TYPE,
				(payload, context) -> RtsSnapshotsClient.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(RtsContentsPayload.TYPE,
				(payload, context) -> RtsContainerViewClient.open(payload.pos(), payload.entries()));
		ClientPlayNetworking.registerGlobalReceiver(RtsBlueprintsPayload.TYPE,
				(payload, context) -> RtsBlueprintsClient.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(RtsBlueprintInfoPayload.TYPE,
				(payload, context) -> RtsBlueprintsClient.acceptInfo(payload));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tick(client);
			syncCutaway();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
	}

	public static boolean isActive() {
		return active;
	}

	public static RtsViewLevel.Mode viewMode() {
		return viewMode;
	}

	public static int viewLevel() {
		return viewLevel;
	}

	/** The highest block Y picks may hit: the view level in level mode, otherwise no limit. */
	public static int pickLevel() {
		return viewMode == RtsViewLevel.Mode.LEVEL ? viewLevel : Integer.MAX_VALUE;
	}

	/** Blocks the view level lies below the surface at the screen centre, or NaN. */
	public static double centreDepth() {
		return centreDepth;
	}

	private static boolean isUnknownBlueprint(Component message) {
		return message.getContents() instanceof TranslatableContents contents
				&& contents.getKey().equals("rabodepalha.rts.order.unknown_blueprint");
	}

	/** Zooms by {@code wheelDelta} notches towards the screen centre; scrolling up lowers the camera. */
	public static void zoom(double wheelDelta) {
		double newHeight = RtsCameraMath.zoom(height, wheelDelta);
		// The shift deliberately uses the target height: it is applied in full at once, so the pivot never
		// drifts, while the applied height catches up within ZOOM_SMOOTH_TIME.
		addOrbitShift(RtsCameraMath.orbitShift(height, yaw, pitch, newHeight, yaw, pitch));
		height = newHeight;
	}

	/** Turns the camera by {@code degrees} of yaw around the screen centre. */
	public static void rotate(float degrees) {
		float newYaw = RtsCameraMath.wrapYaw(yaw + degrees);
		addOrbitShift(RtsCameraMath.orbitShift(height, yaw, pitch, height, newYaw, pitch));
		yaw = newYaw;
	}

	/** Tilts the camera by {@code degrees} of pitch around the screen centre; positive looks further down. */
	public static void tilt(float degrees) {
		float newPitch = RtsCameraMath.tilt(pitch, degrees);
		addOrbitShift(RtsCameraMath.orbitShift(height, yaw, pitch, height, yaw, newPitch));
		pitch = newPitch;
	}

	private static void addOrbitShift(RtsCameraMath.Pan shift) {
		pendingDX += shift.x();
		pendingDZ += shift.z();
	}

	private static void setActive(Minecraft client, boolean value) {
		if (value == active) {
			return;
		}
		active = value;
		RtsSelectionClient.clear();
		RtsCommandClient.clear();
		RtsBlueprintsClient.resetSession();
		if (!value) {
			RtsSnapshotsClient.clear();
			RtsItemPickClient.clear();
			RtsContainerViewClient.close();
		}
		if (value) {
			height = RtsCameraMath.DEFAULT_HEIGHT;
			resetHeight();
			resetViewLevel();
			startUndergroundIfCovered(client);
			pitch = RtsCameraMath.DEFAULT_PITCH;
			yaw = client.player != null ? RtsCameraMath.wrapYaw(client.player.getYRot()) : 0.0f;
			client.mouseHandler.releaseMouse();
		} else if (client.gui.screen() == null) {
			client.mouseHandler.grabMouse();
		}
		syncCutaway();
	}

	/** Hides everything above the view level in level mode, and nothing otherwise. */
	private static void syncCutaway() {
		Cutaway.set(active && viewMode == RtsViewLevel.Mode.LEVEL ? CutawayRegion.above(viewLevel) : CutawayRegion.NONE);
	}

	private static void reset() {
		active = false;
		RtsSelectionClient.clear();
		RtsCommandClient.clear();
		RtsSnapshotsClient.clear();
		RtsItemPickClient.clear();
		RtsContainerViewClient.close();
		RtsBlueprintsClient.resetSession();
		height = RtsCameraMath.DEFAULT_HEIGHT;
		pitch = RtsCameraMath.DEFAULT_PITCH;
		resetHeight();
		resetViewLevel();
		// Re-meshes the cut sections while the level is still there; without a level it only drops the region.
		Cutaway.set(CutawayRegion.NONE);
	}

	private static void resetViewLevel() {
		viewMode = RtsViewLevel.Mode.SURFACE;
		viewLevel = 0;
		centreDepth = Double.NaN;
		levelDirty = false;
		levelUpHeld = -1;
		levelDownHeld = -1;
	}

	/**
	 * Starts in level mode at the player's feet when something covers the player's column, so entering RTS
	 * in a mine or a house does not jump the camera to the surface. The height filter is unset on entry, so
	 * its first tick seeds from the level reference and no rebase is needed.
	 */
	private static void startUndergroundIfCovered(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return;
		}
		int blockX = Mth.floor(player.getX());
		int blockZ = Mth.floor(player.getZ());
		if (!client.level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
			return;
		}
		int feet = Mth.floor(player.getY());
		int surface = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
		if (RtsViewLevel.startsUnderground(feet, surface)) {
			viewMode = RtsViewLevel.Mode.LEVEL;
			viewLevel = RtsViewLevel.clampLevel(feet, client.level.getMinY(), client.level.getMaxY() + 1);
		}
	}

	private static void resetHeight() {
		heightState = RtsCameraMath.HeightState.UNSET;
		lastTickNanos = 0L;
		heightLocked = false;
		lastX = Double.NaN;
		pendingDX = 0.0;
		pendingDZ = 0.0;
	}

	private static void tick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			if (client.player != null && ClientPlayNetworking.canSend(RtsTogglePayload.TYPE)) {
				ClientPlayNetworking.send(RtsTogglePayload.INSTANCE);
			}
		}
		RtsSelectionClient.tick(client);
		RtsSnapshotsClient.syncWatch();
		RtsContainerViewClient.tick();

		LocalPlayer player = client.player;
		if (!active || player == null || client.level == null) {
			while (lockHeightKey.consumeClick()) {
				// Drop presses made outside RTS mode.
			}
			while (rotateBlueprintKey.consumeClick()) {
				// Drop presses made outside RTS mode.
			}
			levelUpHeld = -1;
			levelDownHeld = -1;
			dropCommandPresses();
			RtsCommandClient.clear();
			return;
		}
		while (lockHeightKey.consumeClick()) {
			heightLocked = !heightLocked;
		}

		boolean noScreen = client.gui.screen() == null;
		if (noScreen && client.mouseHandler.isMouseGrabbed()) {
			// Something re-grabbed the cursor (focus change, closing a screen); keep it free.
			client.mouseHandler.releaseMouse();
		}

		if (!noScreen) {
			dropCommandPresses();
			RtsCommandClient.clear();
			levelUpHeld = -1;
			levelDownHeld = -1;
		} else {
			tickViewLevel(client, player);
			for (int i = 0; i < commandKeys.size(); i++) {
				KeyMapping key = commandKeys.get(i);
				while (key.consumeClick()) {
					RtsCommandClient.activate(RtsCommands.COMMANDS.get(i));
				}
			}
			while (rotateBlueprintKey.consumeClick()) {
				if (RtsBlueprintsClient.isArmed()) {
					RtsBlueprintsClient.cycleRotation();
				}
			}
		}

		if (noScreen) {
			if (rotateLeftKey.isDown()) {
				rotate(-RtsCameraMath.ROTATE_STEP_DEG);
			}
			if (rotateRightKey.isDown()) {
				rotate(RtsCameraMath.ROTATE_STEP_DEG);
			}
			if (tiltUpKey.isDown()) {
				tilt(RtsCameraMath.TILT_STEP_DEG);
			}
			if (tiltDownKey.isDown()) {
				tilt(-RtsCameraMath.TILT_STEP_DEG);
			}
		}

		RtsCameraMath.PanInput input = noScreen ? panInput(client) : RtsCameraMath.PanInput.NONE;
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(input.forward(), input.right(), yaw,
				RtsCameraMath.panSpeedForHeight(height));

		double x = player.getX() + pan.x() + pendingDX;
		double z = player.getZ() + pan.z() + pendingDZ;
		pendingDX = 0.0;
		pendingDZ = 0.0;
		double[] ground = sampleGround(client.level, x, z);
		if (levelDirty && !Double.isNaN(ground[0])) {
			// A deliberate level change moves the camera by the whole change in reference, promptly; done before
			// the filter so its dead zone sees the new reference as the ground. With nothing sampled at the new
			// level, retry next tick.
			heightState = RtsCameraMath.rebaseGround(heightState, ground[0]);
			levelDirty = false;
		}
		double reference = ground[0];
		if (!heightState.isSet() && Double.isNaN(reference)) {
			reference = player.getY() - height;
		}
		long now = System.nanoTime();
		double dt = lastTickNanos == 0L ? DEFAULT_TICK_DT : (now - lastTickNanos) / 1.0e9;
		lastTickNanos = now;
		heightState = RtsCameraMath.filterHeight(heightState, reference, ground[1], height, dt, heightLocked);
		double y = heightState.y();

		if (Double.isNaN(lastX)) {
			lastX = player.getX();
			lastY = player.getY();
			lastZ = player.getZ();
			lastYaw = player.getYRot();
		}
		// Interpolate explicitly from the pose applied last tick, whatever the player tick did in between.
		player.setOldPosAndRot(new Vec3(lastX, lastY, lastZ), lastYaw, player.getXRot());
		player.yHeadRotO = lastYaw;

		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(x, y, z);
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setXRot(pitch);
		lastX = x;
		lastY = y;
		lastZ = z;
		lastYaw = yaw;
		centreDepth = sampleCentreDepth(client.level, x, z);
	}

	/** Drops command hotkey and view level presses made outside RTS mode or behind a screen. */
	private static void dropCommandPresses() {
		for (KeyMapping key : commandKeys) {
			while (key.consumeClick()) {
				// Discard.
			}
		}
		for (KeyMapping key : List.of(levelUpKey, levelDownKey, levelModeKey, levelToWorkerKey)) {
			while (key.consumeClick()) {
				// Discard.
			}
		}
	}

	/** Handles the view level keys: mode switch, jump to the selected worker, and held steps. */
	private static void tickViewLevel(Minecraft client, LocalPlayer player) {
		while (levelModeKey.consumeClick()) {
			if (viewMode == RtsViewLevel.Mode.SURFACE) {
				enterLevelMode(client, player);
			} else {
				viewMode = RtsViewLevel.Mode.SURFACE;
				centreDepth = Double.NaN;
				levelDirty = true;
			}
		}
		while (levelToWorkerKey.consumeClick()) {
			List<WorkerEntity> selected = RtsSelectionClient.selectedWorkers();
			if (selected.isEmpty()) {
				RtsHud.showFeedback(false, Component.translatable("rabodepalha.rts.level.no_selection"));
				continue;
			}
			viewMode = RtsViewLevel.Mode.LEVEL;
			setViewLevel(client, Mth.floor(selected.get(0).getY()));
		}
		levelUpHeld = tickLevelStep(client, player, levelUpKey, levelUpHeld, 1);
		levelDownHeld = tickLevelStep(client, player, levelDownKey, levelDownHeld, -1);
	}

	/** Steps the level by {@code delta} while {@code key} is held; returns the new held-tick count. */
	private static int tickLevelStep(Minecraft client, LocalPlayer player, KeyMapping key, int held, int delta) {
		boolean clicked = false;
		while (key.consumeClick()) {
			clicked = true;
		}
		int next = key.isDown() ? held + 1 : -1;
		// A tap shorter than a tick is a click without the key still down: step once.
		if ((next >= 0 && RtsViewLevel.repeatStep(next)) || (clicked && held < 0 && next < 0)) {
			if (viewMode == RtsViewLevel.Mode.SURFACE) {
				enterLevelMode(client, player);
			}
			setViewLevel(client, viewLevel + delta);
		}
		return next;
	}

	/**
	 * Switches to level mode at the feet of whoever stands on the ground the camera follows (the block above
	 * its top), or at the player's feet. The level is always a feet block, as when entering underground or
	 * jumping to a worker, so workers standing on the followed ground stay pickable.
	 */
	private static void enterLevelMode(Minecraft client, LocalPlayer player) {
		viewMode = RtsViewLevel.Mode.LEVEL;
		int level = heightState.isSet() ? Mth.floor(heightState.ground()) : Mth.floor(player.getY());
		setViewLevel(client, level);
	}

	private static void setViewLevel(Minecraft client, int level) {
		viewLevel = client.level != null
				? RtsViewLevel.clampLevel(level, client.level.getMinY(), client.level.getMaxY() + 1)
				: level;
		levelDirty = true;
	}

	/**
	 * Samples the ground around the camera column at ({@code x}, {@code z}) and the area it looks at.
	 * Returns {reference, highest}: the weighted percentile of {@code MOTION_BLOCKING_NO_LEAVES}, and the
	 * highest {@code MOTION_BLOCKING} column near the camera. Columns in unloaded chunks are skipped, so
	 * either is NaN when nothing sampled is loaded. In level mode both come from scanning each column down
	 * from the view level instead of the heightmaps; a column with nothing solid in reach is skipped too.
	 */
	private static double[] sampleGround(Level level, double x, double z) {
		List<RtsCameraMath.Offset> offsets = RtsCameraMath.sampleOffsets(height, yaw, pitch);
		double[] reference = new double[offsets.size()];
		double[] weights = new double[offsets.size()];
		double[] highest = new double[offsets.size()];
		boolean levelMode = viewMode == RtsViewLevel.Mode.LEVEL;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < offsets.size(); i++) {
			RtsCameraMath.Offset offset = offsets.get(i);
			int blockX = Mth.floor(x + offset.dx());
			int blockZ = Mth.floor(z + offset.dz());
			weights[i] = offset.weight();
			if (!level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
				reference[i] = Double.NaN;
				highest[i] = Double.NaN;
				continue;
			}
			if (levelMode) {
				reference[i] = levelColumnTop(level, pos, blockX, blockZ, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
				highest[i] = offset.nearCamera()
						? levelColumnTop(level, pos, blockX, blockZ, Heightmap.Types.MOTION_BLOCKING)
						: Double.NaN;
				continue;
			}
			reference[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
			highest[i] = offset.nearCamera() ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
					: Double.NaN;
		}
		return new double[] {
			RtsCameraMath.weightedPercentile(reference, weights, RtsCameraMath.SAMPLE_PERCENTILE),
			RtsCameraMath.maxFinite(highest)
		};
	}

	/** A column's height seen from the view level, counting the blocks {@code type}'s heightmap counts. */
	private static double levelColumnTop(Level level, BlockPos.MutableBlockPos pos, int blockX, int blockZ,
			Heightmap.Types type) {
		return RtsViewLevel.columnTop(viewLevel, level.getMinY(), RtsViewLevel.SCAN_LIMIT,
				y -> type.isOpaque().test(level.getBlockState(pos.set(blockX, y, blockZ))));
	}

	/** How far the view level lies below the surface at the column the screen centre looks at, or NaN. */
	private static double sampleCentreDepth(Level level, double x, double z) {
		if (viewMode != RtsViewLevel.Mode.LEVEL) {
			return Double.NaN;
		}
		RtsCameraMath.Pan look = RtsCameraMath.lookAtPoint(x, z, height, yaw, pitch);
		int blockX = Mth.floor(look.x());
		int blockZ = Mth.floor(look.z());
		if (!level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
			return Double.NaN;
		}
		return RtsViewLevel.depthBelowSurface(
				level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ), viewLevel);
	}

	/** WASD (through the vanilla movement bindings) plus edge scroll, in screen-relative terms. */
	private static RtsCameraMath.PanInput panInput(Minecraft client) {
		double forward = 0.0;
		double right = 0.0;
		if (client.options.keyUp.isDown()) {
			forward += 1.0;
		}
		if (client.options.keyDown.isDown()) {
			forward -= 1.0;
		}
		if (client.options.keyRight.isDown()) {
			right += 1.0;
		}
		if (client.options.keyLeft.isDown()) {
			right -= 1.0;
		}
		RtsCameraMath.PanInput keys = new RtsCameraMath.PanInput(forward, right);
		if (!client.isWindowActive() || RtsSelectionClient.isDragging() || RtsCommandClient.isAreaDragging()) {
			// No edge scroll while dragging a selection box or dig area, which often ends at the screen edge.
			return keys;
		}
		Window window = client.getWindow();
		return keys.plus(RtsCameraMath.edgeScroll(client.mouseHandler.xpos(), client.mouseHandler.ypos(),
				window.getScreenWidth(), window.getScreenHeight(), RtsCameraMath.EDGE_MARGIN_PX));
	}
}
