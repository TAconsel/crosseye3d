package com.consel.crosseye3d;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class StereoConfig {
	public enum Layout {
		/** Each eye fills half the screen width at full height (vertical FOV kept, image is taller than wide). */
		FULL,
		/** Each eye keeps the normal aspect ratio at half size, with black bars above and below. Easier to fuse. */
		LETTERBOX
	}

	public static final float MIN_SEPARATION = 0.01F;
	public static final float MAX_SEPARATION = 2.0F;
	public static final float MIN_CONVERGENCE = 0.5F;
	public static final float MAX_CONVERGENCE = 64.0F;

	public boolean enabled = true;
	/** true = cross-eye (right-eye image on the left half), false = parallel / wall-eye. */
	public boolean crossEye = true;
	public Layout layout = Layout.FULL;
	/** Distance between the two virtual cameras, in blocks. */
	public float eyeSeparation = 0.25F;
	/** Distance in blocks that appears at the screen plane (zero parallax). */
	public float convergence = 5.0F;

	/** Head-coupled perspective from a head tracker sending UDP packets (see HeadTracker). */
	public boolean headTracking = false;
	/** Viewing distance used when the tracker only sends positions relative to a centre pose. */
	public float viewingDistanceCm = 60.0F;
	/** Scales how far the view follows the head (1 = same angles as your real head movement). */
	public float trackingStrength = 1.0F;
	public int trackerPort = 4242;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("crosseye3d.properties");
	}

	public static StereoConfig load() {
		StereoConfig config = new StereoConfig();
		Path path = path();
		if (Files.isRegularFile(path)) {
			Properties props = new Properties();
			try (Reader reader = Files.newBufferedReader(path)) {
				props.load(reader);
				config.enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
				config.crossEye = Boolean.parseBoolean(props.getProperty("crossEye", "true"));
				config.layout = Layout.valueOf(props.getProperty("layout", "FULL"));
				config.eyeSeparation = Float.parseFloat(props.getProperty("eyeSeparation", "0.25"));
				config.convergence = Float.parseFloat(props.getProperty("convergence", "5.0"));
				config.headTracking = Boolean.parseBoolean(props.getProperty("headTracking", "false"));
				config.viewingDistanceCm = Float.parseFloat(props.getProperty("viewingDistanceCm", "60.0"));
				config.trackingStrength = Float.parseFloat(props.getProperty("trackingStrength", "1.0"));
				config.trackerPort = Integer.parseInt(props.getProperty("trackerPort", "4242"));
			} catch (IOException | IllegalArgumentException e) {
				CrossEye3DClient.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		config.clamp();
		return config;
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(this.enabled));
		props.setProperty("crossEye", Boolean.toString(this.crossEye));
		props.setProperty("layout", this.layout.name());
		props.setProperty("eyeSeparation", Float.toString(this.eyeSeparation));
		props.setProperty("convergence", Float.toString(this.convergence));
		props.setProperty("headTracking", Boolean.toString(this.headTracking));
		props.setProperty("viewingDistanceCm", Float.toString(this.viewingDistanceCm));
		props.setProperty("trackingStrength", Float.toString(this.trackingStrength));
		props.setProperty("trackerPort", Integer.toString(this.trackerPort));
		try (Writer writer = Files.newBufferedWriter(path())) {
			props.store(writer, "CrossEye 3D settings");
		} catch (IOException e) {
			CrossEye3DClient.LOGGER.warn("Could not save {}", path(), e);
		}
	}

	public void clamp() {
		this.eyeSeparation = Math.clamp(this.eyeSeparation, MIN_SEPARATION, MAX_SEPARATION);
		this.convergence = Math.clamp(this.convergence, MIN_CONVERGENCE, MAX_CONVERGENCE);
		this.viewingDistanceCm = Math.clamp(this.viewingDistanceCm, 20.0F, 1000.0F);
		this.trackingStrength = Math.clamp(this.trackingStrength, 0.0F, 5.0F);
		this.trackerPort = Math.clamp(this.trackerPort, 1, 65535);
	}
}
