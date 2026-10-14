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
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.network.RtsBlueprintInfoPayload;
import io.github.oshogun.rabodepalha.network.RtsBlueprintsPayload;
import io.github.oshogun.rabodepalha.network.RtsContentsPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderFeedbackPayload;
import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsStatePayload;
import io.github.oshogun.rabodepalha.network.RtsTogglePayload;
import io.github.oshogun.rabodepalha.rts.RtsCameraMath;

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
		ClientTickEvents.END_CLIENT_TICK.register(RtsClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
	}

	public static boolean isActive() {
		return active;
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
			pitch = RtsCameraMath.DEFAULT_PITCH;
			yaw = client.player != null ? RtsCameraMath.wrapYaw(client.player.getYRot()) : 0.0f;
			client.mouseHandler.releaseMouse();
		} else if (client.gui.screen() == null) {
			client.mouseHandler.grabMouse();
		}
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
		} else {
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
	}

	/** Drops command hotkey presses made outside RTS mode or behind a screen. */
	private static void dropCommandPresses() {
		for (KeyMapping key : commandKeys) {
			while (key.consumeClick()) {
				// Discard.
			}
		}
	}

	/**
	 * Samples the ground around the camera column at ({@code x}, {@code z}) and the area it looks at.
	 * Returns {reference, highest}: the weighted percentile of {@code MOTION_BLOCKING_NO_LEAVES}, and the
	 * highest {@code MOTION_BLOCKING} column near the camera. Columns in unloaded chunks are skipped, so
	 * either is NaN when nothing sampled is loaded.
	 */
	private static double[] sampleGround(Level level, double x, double z) {
		List<RtsCameraMath.Offset> offsets = RtsCameraMath.sampleOffsets(height, yaw, pitch);
		double[] reference = new double[offsets.size()];
		double[] weights = new double[offsets.size()];
		double[] highest = new double[offsets.size()];
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
			reference[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
			highest[i] = offset.nearCamera() ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
					: Double.NaN;
		}
		return new double[] {
			RtsCameraMath.weightedPercentile(reference, weights, RtsCameraMath.SAMPLE_PERCENTILE),
			RtsCameraMath.maxFinite(highest)
		};
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
