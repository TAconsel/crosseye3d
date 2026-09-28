package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.StereoRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/**
	 * Entity, block entity and block breaking render states are extracted once per frame and cleared as soon as
	 * they are submitted. Keep them until the last eye has been rendered, otherwise only the first eye has entities.
	 */
	@WrapOperation(
		method = "submitFeatures",
		at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V")
	)
	private void crosseye3d$keepRenderStatesForNextEye(final List<?> list, final Operation<Void> original) {
		if (!StereoRenderer.hasMoreEyes()) {
			original.call(list);
		}
	}
}
