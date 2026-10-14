package io.github.oshogun.rabodepalha.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;
import io.github.oshogun.rabodepalha.client.rts.RtsCommandClient;
import io.github.oshogun.rabodepalha.client.rts.RtsOrdersClient;
import io.github.oshogun.rabodepalha.client.rts.RtsPanelClient;
import io.github.oshogun.rabodepalha.client.rts.RtsSelectionClient;
import io.github.oshogun.rabodepalha.rts.RtsCameraMath;

/**
 * Mouse handling in RTS mode, while no screen is open: clicks neither grab the cursor nor attack or use,
 * the left button uses the side panel or the command bar, fires the armed command or selects workers (click or box drag), the right
 * button disarms the armed command or orders a move, the wheel sets a dig area's depth or else zooms instead of
 * changing the spectator fly speed, and dragging with the middle button rotates (horizontally) and tilts (vertically)
 * the camera. The drag uses cursor-position deltas rather than vanilla's accumulated movement.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	@Unique
	private boolean rabodepalha$middleDragging;

	@Unique
	private boolean rabodepalha$swallowLeftRelease;

	@Unique
	private double rabodepalha$dragLastX;

	@Unique
	private double rabodepalha$dragLastY;

	@Unique
	private boolean rabodepalha$rtsWorldInput(long handle) {
		return RtsClient.isActive()
				&& handle != 0L
				&& handle == this.minecraft.getWindow().handle()
				&& this.minecraft.gui.screen() == null
				&& this.minecraft.gui.overlay() == null;
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$rtsButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
		if (!this.rabodepalha$rtsWorldInput(handle)) {
			this.rabodepalha$middleDragging = false;
			this.rabodepalha$swallowLeftRelease = false;
			// A screen opened mid-drag: the box can no longer finish.
			RtsSelectionClient.cancelDrag();
			return;
		}
		if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			this.rabodepalha$middleDragging = action == 1;
			this.rabodepalha$dragLastX = this.xpos;
			this.rabodepalha$dragLastY = this.ypos;
		} else if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (action == 1) {
				// The panel comes first: a press on it neither fires the armed command nor selects.
				if (RtsPanelClient.onLeftPress(this.xpos, this.ypos)
						|| RtsCommandClient.onLeftPress(this.xpos, this.ypos, rawButtonInfo.hasShiftDown())) {
					// The panel, the bar or the armed mode took the press: its release must not finish a selection.
					this.rabodepalha$swallowLeftRelease = true;
				} else {
					RtsSelectionClient.onLeftPress(this.xpos, this.ypos);
				}
			} else if (action == 0) {
				// An area drag ends here and enters its depth step; its press already set the swallow flag.
				RtsCommandClient.onLeftRelease();
				if (this.rabodepalha$swallowLeftRelease) {
					this.rabodepalha$swallowLeftRelease = false;
				} else {
					RtsSelectionClient.onLeftRelease(this.xpos, this.ypos, rawButtonInfo.hasShiftDown());
				}
			}
		} else if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT && action == 1) {
			// Right-clicks on the bar or the panel do nothing; elsewhere they disarm the armed command, or order a
			// plain move.
			if (!RtsCommandClient.overBar(this.xpos, this.ypos) && !RtsPanelClient.over(this.xpos, this.ypos)
					&& !RtsCommandClient.onRightClick()) {
				RtsOrdersClient.onRightClick(this.xpos, this.ypos, rawButtonInfo.hasShiftDown());
			}
		}
		ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$rtsZoom(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (!this.rabodepalha$rtsWorldInput(handle) || this.minecraft.player == null) {
			return;
		}
		if (RtsPanelClient.over(this.xpos, this.ypos)) {
			// The wheel over the panel neither zooms nor sets a dig area's depth.
			ci.cancel();
			return;
		}
		boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
				|| InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
		// Raw sign on purpose: positive is wheel up, which RtsAreaDrag.scroll maps to a shallower area.
		if (RtsCommandClient.onScroll(yoffset, shift)) {
			this.minecraft.getFramerateLimitTracker().onInputReceived();
			ci.cancel();
			return;
		}
		boolean discreteScroll = this.minecraft.options.discreteMouseScroll().get();
		double sensitivity = this.minecraft.options.mouseWheelSensitivity().get();
		RtsClient.zoom((discreteScroll ? Math.signum(yoffset) : yoffset) * sensitivity);
		this.minecraft.getFramerateLimitTracker().onInputReceived();
		ci.cancel();
	}

	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void rabodepalha$rtsDragRotate(CallbackInfo ci) {
		if (!RtsClient.isActive() || this.minecraft.gui.screen() != null || this.minecraft.gui.overlay() != null) {
			this.rabodepalha$middleDragging = false;
			return;
		}
		RtsSelectionClient.onDragMove(this.xpos, this.ypos);
		RtsCommandClient.onDragMove(this.xpos, this.ypos);
		if (this.rabodepalha$middleDragging && this.minecraft.isWindowActive()) {
			double dx = this.xpos - this.rabodepalha$dragLastX;
			double dy = this.ypos - this.rabodepalha$dragLastY;
			// Same sense as mouselook: drag right turns right, drag down looks further down.
			if (dx != 0.0) {
				RtsClient.rotate((float) (dx * RtsCameraMath.DRAG_ROTATE_DEG_PER_PIXEL));
			}
			if (dy != 0.0) {
				RtsClient.tilt((float) (dy * RtsCameraMath.DRAG_TILT_DEG_PER_PIXEL));
			}
		}
		this.rabodepalha$dragLastX = this.xpos;
		this.rabodepalha$dragLastY = this.ypos;
	}
}
