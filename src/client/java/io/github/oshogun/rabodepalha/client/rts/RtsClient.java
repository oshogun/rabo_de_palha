package io.github.oshogun.rabodepalha.client.rts;

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

	/** How much of the gap to the target camera height is closed per tick. */
	private static final double HEIGHT_SMOOTHING = 0.35;

	private static boolean active;
	private static double height = RtsCameraMath.DEFAULT_HEIGHT;
	private static float yaw;
	/** Last known ground height under the camera; kept while the chunk below is not loaded yet. */
	private static double groundY = Double.NaN;

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
			groundY = Double.NaN;
			yaw = client.player != null ? RtsCameraMath.wrapYaw(client.player.getYRot()) : 0.0f;
			client.mouseHandler.releaseMouse();
		} else if (client.gui.screen() == null) {
			client.mouseHandler.grabMouse();
		}
	}

	private static void reset() {
		active = false;
		RtsSelectionClient.clear();
		height = RtsCameraMath.DEFAULT_HEIGHT;
		groundY = Double.NaN;
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
			return;
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
		int blockX = Mth.floor(x);
		int blockZ = Mth.floor(z);
		if (client.level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
			groundY = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
		} else if (Double.isNaN(groundY)) {
			groundY = player.getY() - height;
		}
		double targetY = groundY + height;
		double y = player.getY() + (targetY - player.getY()) * HEIGHT_SMOOTHING;

		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(x, y, z);
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setXRot(RtsCameraMath.DEFAULT_PITCH);
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
