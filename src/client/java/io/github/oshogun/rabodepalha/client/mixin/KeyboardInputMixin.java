package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;

/**
 * In RTS mode WASD pans the camera ({@link RtsClient} reads the bindings itself), so the player must not
 * also fly with them: clear the movement input, including jump and sneak, which would fly up and down.
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void rabodepalha$clearRtsMovement(CallbackInfo ci) {
		if (RtsClient.isActive()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}
