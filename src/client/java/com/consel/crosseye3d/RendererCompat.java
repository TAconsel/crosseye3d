package com.consel.crosseye3d;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Sodium and Iris cache per-frame GPU state on the assumption that the level is rendered once per frame.
 * Both caches are reset before the second eye so it gets its own projection:
 * <ul>
 *   <li>Sodium uploads its terrain uniforms (including the projection matrix) once per frame. Without a reset, the
 *   second eye's terrain reuses the first eye's projection while entities, outlines and the hand don't, so the two
 *   halves have identical terrain with shifted overlays (a ghost image that grows with the depth setting).</li>
 *   <li>Iris only refreshes per-frame shader uniforms (gbufferProjection, its inverse, previous-frame matrices, ...)
 *   when its frame counter changes, so deferred lighting would be reconstructed with the wrong eye's matrices.</li>
 * </ul>
 */
final class RendererCompat {
	/** {@code SodiumWorldRenderer.instanceNullable()}. */
	private static final @Nullable MethodHandle SODIUM_RENDERER = findSodiumRenderer();
	/** {@code SodiumWorldRenderer.uniformBufferManager} getter. */
	private static final @Nullable MethodHandle SODIUM_UNIFORMS = findSodiumUniforms();
	/** {@code UniformBufferManager.prepareFrame()}, which clears its updated-this-frame flag. */
	private static final @Nullable MethodHandle SODIUM_PREPARE_FRAME = findSodiumPrepareFrame();
	/** {@code SystemTimeUniforms.COUNTER.beginFrame()}. */
	private static final @Nullable MethodHandle IRIS_BEGIN_FRAME = findIrisBeginFrame();

	private RendererCompat() {
	}

	/** Call between the two eyes so the second one is treated as a fresh level render. */
	static void beginSecondEye() {
		try {
			if (SODIUM_RENDERER != null && SODIUM_UNIFORMS != null && SODIUM_PREPARE_FRAME != null) {
				Object renderer = SODIUM_RENDERER.invoke();
				if (renderer != null) {
					Object uniforms = SODIUM_UNIFORMS.invoke(renderer);
					if (uniforms != null) {
						SODIUM_PREPARE_FRAME.invoke(uniforms);
					}
				}
			}

			if (IRIS_BEGIN_FRAME != null) {
				IRIS_BEGIN_FRAME.invoke();
			}
		} catch (Throwable e) {
			throw new RuntimeException("Failed to reset renderer state for the second eye", e);
		}
	}

	private static final String SODIUM_RENDERER_CLASS = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
	private static final String SODIUM_UNIFORMS_CLASS = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager";

	private static @Nullable MethodHandle findSodiumRenderer() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return null;
		}
		try {
			Class<?> renderer = Class.forName(SODIUM_RENDERER_CLASS);
			return MethodHandles.publicLookup().findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
		} catch (ReflectiveOperationException | RuntimeException e) {
			warn("Sodium world renderer", e);
			return null;
		}
	}

	private static @Nullable MethodHandle findSodiumUniforms() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return null;
		}
		try {
			Field field = Class.forName(SODIUM_RENDERER_CLASS).getDeclaredField("uniformBufferManager");
			field.setAccessible(true);
			return MethodHandles.lookup().unreflectGetter(field);
		} catch (ReflectiveOperationException | RuntimeException e) {
			warn("Sodium terrain uniforms", e);
			return null;
		}
	}

	private static @Nullable MethodHandle findSodiumPrepareFrame() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return null;
		}
		try {
			Class<?> uniforms = Class.forName(SODIUM_UNIFORMS_CLASS);
			return MethodHandles.publicLookup().findVirtual(uniforms, "prepareFrame", MethodType.methodType(void.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			warn("Sodium terrain uniforms", e);
			return null;
		}
	}

	private static @Nullable MethodHandle findIrisBeginFrame() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return null;
		}
		try {
			Class<?> uniforms = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
			Object counter = uniforms.getField("COUNTER").get(null);
			return MethodHandles.publicLookup()
				.findVirtual(counter.getClass(), "beginFrame", MethodType.methodType(void.class))
				.bindTo(counter);
		} catch (ReflectiveOperationException | RuntimeException e) {
			warn("Iris frame counter", e);
			return null;
		}
	}

	private static void warn(final String what, final Throwable e) {
		CrossEye3DClient.LOGGER.warn("Could not hook the {}; one eye may render with the other eye's projection", what, e);
	}
}
