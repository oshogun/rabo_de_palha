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
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.network.RtsOrderFeedbackPayload;
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
	/** Held while right-clicking to give a mine order instead of a move order. */
	public static KeyMapping mineOrderKey;
	/** Held while right-clicking to place the selected hotbar block. */
	public static KeyMapping placeOrderKey;

	/** Toggles freezing the camera height while panning. */
	public static KeyMapping lockHeightKey;

	/** Time step assumed for the first tick, when there is no previous tick to measure from. */
	private static final double DEFAULT_TICK_DT = 0.05;

	private static boolean active;
	private static double height = RtsCameraMath.DEFAULT_HEIGHT;
	private static float yaw;
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
		mineOrderKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_mine_order",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT, CATEGORY));
		placeOrderKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_place_order",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_LCONTROL, CATEGORY));
		lockHeightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rabodepalha.rts_lock_height",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(RtsStatePayload.TYPE,
				(payload, context) -> setActive(context.client(), payload.active()));
		ClientPlayNetworking.registerGlobalReceiver(RtsOrderFeedbackPayload.TYPE,
				(payload, context) -> RtsHud.showFeedback(payload.ok(), payload.message()));
		ClientTickEvents.END_CLIENT_TICK.register(RtsClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
	}

	public static boolean isActive() {
		return active;
	}

	/** Zooms by {@code wheelDelta} notches; scrolling up lowers the camera. */
	public static void zoom(double wheelDelta) {
		height = RtsCameraMath.zoom(height, wheelDelta);
	}

	/** Turns the camera by {@code degrees} of yaw. */
	public static void rotate(float degrees) {
		yaw = RtsCameraMath.wrapYaw(yaw + degrees);
	}

	private static void setActive(Minecraft client, boolean value) {
		if (value == active) {
			return;
		}
		active = value;
		RtsSelectionClient.clear();
		if (value) {
			height = RtsCameraMath.DEFAULT_HEIGHT;
			resetHeight();
			yaw =client.player != null ? RtsCameraMath.wrapYaw(client.player.getYRot()) : 0.0f;
			client.mouseHandler.releaseMouse();
		} else if (client.gui.screen() == null) {
			client.mouseHandler.grabMouse();
		}
	}

	private static void reset() {
		active = false;
		RtsSelectionClient.clear();
		height = RtsCameraMath.DEFAULT_HEIGHT;
		resetHeight();
	}

	private static void resetHeight() {
		heightState = RtsCameraMath.HeightState.UNSET;
		lastTickNanos = 0L;
		heightLocked = false;
		lastX = Double.NaN;
	}

	private static void tick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			if (client.player != null && ClientPlayNetworking.canSend(RtsTogglePayload.TYPE)) {
				ClientPlayNetworking.send(RtsTogglePayload.INSTANCE);
			}
		}
		RtsSelectionClient.tick(client);

		LocalPlayer player = client.player;
		if (!active || player == null || client.level == null) {
			while (lockHeightKey.consumeClick()) {
				// Drop presses made outside RTS mode.
			}
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

		if (noScreen) {
			if (rotateLeftKey.isDown()) {
				rotate(-RtsCameraMath.ROTATE_STEP_DEG);
			}
			if (rotateRightKey.isDown()) {
				rotate(RtsCameraMath.ROTATE_STEP_DEG);
			}
		}

		RtsCameraMath.PanInput input = noScreen ? panInput(client) : RtsCameraMath.PanInput.NONE;
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(input.forward(), input.right(), yaw,
				RtsCameraMath.panSpeedForHeight(height));

		double x = player.getX() + pan.x();
		double z = player.getZ() + pan.z();
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
		player.setXRot(RtsCameraMath.DEFAULT_PITCH);
		lastX = x;
		lastY = y;
		lastZ = z;
		lastYaw = yaw;
	}

	/**
	 * Samples the ground around the camera column at ({@code x}, {@code z}) and the area it looks at.
	 * Returns {reference, highest}: the weighted percentile of {@code MOTION_BLOCKING_NO_LEAVES}, and the
	 * highest {@code MOTION_BLOCKING} column near the camera. Columns in unloaded chunks are skipped, so
	 * either is NaN when nothing sampled is loaded.
	 */
	private static double[] sampleGround(Level level, double x, double z) {
		List<RtsCameraMath.Offset> offsets = RtsCameraMath.sampleOffsets(height, yaw, RtsCameraMath.DEFAULT_PITCH);
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
		if (!client.isWindowActive() || RtsSelectionClient.isDragging()) {
			// No edge scroll while dragging a selection box, which often ends at the screen edge.
			return keys;
		}
		Window window = client.getWindow();
		return keys.plus(RtsCameraMath.edgeScroll(client.mouseHandler.xpos(), client.mouseHandler.ypos(),
				window.getScreenWidth(), window.getScreenHeight(), RtsCameraMath.EDGE_MARGIN_PX));
	}
}
