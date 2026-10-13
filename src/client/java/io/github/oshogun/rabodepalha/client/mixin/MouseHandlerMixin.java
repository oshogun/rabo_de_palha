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
import io.github.oshogun.rabodepalha.client.rts.RtsOrdersClient;
import io.github.oshogun.rabodepalha.client.rts.RtsSelectionClient;
import io.github.oshogun.rabodepalha.rts.RtsCameraMath;

/**
 * Mouse handling in RTS mode, while no screen is open: clicks neither grab the cursor nor attack or use,
 * the left button selects workers (click or box drag), the right button orders them, the wheel zooms instead of changing the
 * spectator fly speed, and dragging with the middle button rotates the camera.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	@Unique
	private boolean rabodepalha$middleDragging;

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
			// A screen opened mid-drag: the box can no longer finish.
			RtsSelectionClient.cancelDrag();
			return;
		}
		if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			this.rabodepalha$middleDragging = action == 1;
		} else if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (action == 1) {
				RtsSelectionClient.onLeftPress(this.xpos, this.ypos);
			} else if (action == 0) {
				RtsSelectionClient.onLeftRelease(this.xpos, this.ypos, rawButtonInfo.hasShiftDown());
			}
		} else if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT && action == 1) {
			RtsOrdersClient.onRightClick(this.xpos, this.ypos, rawButtonInfo.hasShiftDown());
		}
		ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$rtsZoom(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (!this.rabodepalha$rtsWorldInput(handle) || this.minecraft.player == null) {
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
		if (!RtsClient.isActive() || this.minecraft.gui.screen() != null) {
			this.rabodepalha$middleDragging = false;
			return;
		}
		RtsSelectionClient.onDragMove(this.xpos, this.ypos);
		if (this.rabodepalha$middleDragging && this.minecraft.isWindowActive() && this.accumulatedDX != 0.0) {
			RtsClient.rotate((float) (this.accumulatedDX * RtsCameraMath.DRAG_ROTATE_DEG_PER_PIXEL));
		}
	}
}
