package codes.dreaming.wireguard;

import codes.dreaming.wireguard.jni.Native;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client-side mod initializer for WireGuard Tunnel.
 * <p>
 * This initializer loads the native library and starts the WARP tunnel
 * during client initialization. The tunnel must be ready before any
 * server connections are attempted.
 */
public class WireguardTunnelClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("wireguard-tunnel");

	/**
	 * Path to store WARP credentials, relative to the game config directory.
	 */
	private static final String WARP_CREDENTIALS_PATH = "wireguard-tunnel/warp-credentials.json";

	/**
	 * Initial delay between retry attempts in milliseconds.
	 */
	private static final long INITIAL_RETRY_DELAY_MS = 1000;

	/**
	 * Maximum delay between retry attempts in milliseconds (30 seconds).
	 */
	private static final long MAX_RETRY_DELAY_MS = 30000;

	private static boolean tunnelReady = false;
	private static boolean tunnelFailed = false;
	private static String failureReason = null;
	private static boolean toastShown = false;
	private static volatile boolean tunnelConnecting = false;
	private static volatile Thread connectThread = null;

	static {
		// Load native library and initialize JNI
		try {
			NativeLibraryLoader.loadLibrary("wireguard_tunnel_jni");
			Native.initJNI();
			LOGGER.info("Native library loaded successfully!");
			LOGGER.info("Native ping: {}", Native.ping());
			LOGGER.info("Native version: {}", Native.version());
		} catch (Throwable t) {
			LOGGER.error("Failed to load native library", t);
			tunnelFailed = true;
			failureReason = "Failed to load native library: " + t.getMessage();
		}
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info("WireGuard Tunnel client initializing...");

		// If native library failed to load, skip tunnel initialization
		if (tunnelFailed) {
			LOGGER.warn("Skipping tunnel initialization due to previous failure");
			registerToastCallback();
			return;
		}

		// Check if WARP is enabled in config before starting the tunnel
		if (!WireguardConfig.getInstance().isWarpEnabled()) {
			LOGGER.info("WARP is disabled in config, skipping tunnel initialization");
			return;
		}

		// Start the WARP tunnel
		try {
			startTunnel();
			LOGGER.info("WireGuard Tunnel client initialized, tunnel state: {}",
					Native.tunnelStateToString(Native.tunnelState()));
		} catch (Exception e) {
			LOGGER.error("Failed to start WARP tunnel", e);
			tunnelFailed = true;
			failureReason = "Failed to connect to WARP";
			registerToastCallback();
		}
	}

	/**
	 * Register a client tick callback to show the toast once the client is ready.
	 * We wait until the overlay is null (loading screen finished) and toasts are available.
	 */
	private void registerToastCallback() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!toastShown && client.getOverlay() == null && client.getToasts() != null) {
				toastShown = true;
				client.getToasts().addToast(new SystemToast(
						SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
						Component.literal("WARP Connection Failed"),
						Component.literal(failureReason != null ? failureReason : "Unknown error")
				));
			}
		});
	}

	/**
	 * Start the WARP tunnel.
	 * <p>
	 * This will load or generate WARP credentials and establish the tunnel.
	 * If credentials don't exist, a new WARP device will be registered.
	 */
	private void startTunnel() {
		// Get the config directory path
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path credPath = configDir.resolve(WARP_CREDENTIALS_PATH);

		LOGGER.info("Starting WARP tunnel with credentials from: {}", credPath);

		int state = Native.startWarpTunnel(credPath.toString());

		if (state != Native.TUNNEL_STATE_READY) {
			throw new RuntimeException("Tunnel failed to start, state: " +
					Native.tunnelStateToString(state));
		}

		tunnelReady = true;
		LOGGER.info("WARP tunnel started successfully!");
	}

	/**
	 * Check if the tunnel is ready for connections.
	 *
	 * @return true if the tunnel is ready
	 */
	public static boolean isTunnelReady() {
		return tunnelReady && !tunnelFailed && Native.isTunnelReady();
	}

	/**
	 * Check if the tunnel failed to initialize.
	 *
	 * @return true if the tunnel failed
	 */
	public static boolean isTunnelFailed() {
		return tunnelFailed;
	}

	/**
	 * Shutdown the tunnel.
	 * <p>
	 * This should be called when the client is shutting down.
	 */
	public static void shutdownTunnel() {
		// Interrupt any ongoing connection attempt
		if (connectThread != null && connectThread.isAlive()) {
			connectThread.interrupt();
			connectThread = null;
		}
		tunnelConnecting = false;

		if (tunnelReady) {
			LOGGER.info("Shutting down WARP tunnel...");
			Native.shutdownTunnel();
			tunnelReady = false;
			LOGGER.info("WARP tunnel shut down");
		}

		// Reset failure state so tunnel can be restarted
		tunnelFailed = false;
		failureReason = null;
	}

	/**
	 * Stop the tunnel with user feedback (e.g., when disabled via button).
	 * Shows a toast notification confirming the tunnel has been stopped.
	 */
	public static void stopTunnelWithFeedback() {
		shutdownTunnel();
		showToast("WARP Disconnected", "Tunnel has been stopped");
	}

	/**
	 * Start the tunnel on-demand (e.g., when enabled via button) with user feedback.
	 * Shows a toast notification indicating success or failure.
	 * Will retry indefinitely with exponential backoff (max 30s) while WARP is still enabled.
	 * <p>
	 * This method should be called from the UI thread and will start the tunnel
	 * in a background thread to avoid blocking the game.
	 */
	public static void startTunnelWithFeedback() {
		if (tunnelReady) {
			LOGGER.info("Tunnel already running");
			showToast("WARP Already Connected", "Tunnel is already running");
			return;
		}

		if (tunnelConnecting) {
			LOGGER.info("Tunnel connection already in progress");
			showToast("WARP Connecting", "Connection attempt in progress...");
			return;
		}

		// Reset failure state for new attempt
		tunnelFailed = false;
		failureReason = null;

		showToast("WARP Connecting", "Starting tunnel...");

		// Start tunnel in background thread to avoid blocking the UI
		connectThread = new Thread(() -> {
			tunnelConnecting = true;
			int attempt = 0;
			long currentDelay = INITIAL_RETRY_DELAY_MS;

			try {
				while (true) {
					// Check if WARP is still enabled before each attempt
					if (!WireguardConfig.getInstance().isWarpEnabled()) {
						LOGGER.info("WARP disabled during connection, aborting");
						tunnelConnecting = false;
						return;
					}

					// Check if thread was interrupted (tunnel shutdown requested)
					if (Thread.currentThread().isInterrupted()) {
						LOGGER.info("Tunnel connection interrupted");
						tunnelConnecting = false;
						return;
					}

					attempt++;
					LOGGER.info("Starting WARP tunnel (attempt {})", attempt);

					try {
						Path configDir = FabricLoader.getInstance().getConfigDir();
						Path credPath = configDir.resolve(WARP_CREDENTIALS_PATH);

						int state = Native.startWarpTunnel(credPath.toString());

						if (state == Native.TUNNEL_STATE_READY) {
							tunnelReady = true;
							tunnelConnecting = false;
							LOGGER.info("WARP tunnel started successfully!");
							showToast("WARP Connected", "Tunnel is now active");
							return;
						}

						failureReason = "Tunnel state: " + Native.tunnelStateToString(state);
						LOGGER.warn("Tunnel not ready: {} (attempt {})", failureReason, attempt);

					} catch (Exception e) {
						failureReason = e.getMessage();
						LOGGER.warn("Failed to start tunnel (attempt {}): {}", attempt, e.getMessage());
					}

					// Check again if WARP is still enabled before sleeping
					if (!WireguardConfig.getInstance().isWarpEnabled()) {
						LOGGER.info("WARP disabled during connection, aborting");
						tunnelConnecting = false;
						return;
					}

					// Show retry toast with current delay
					long delaySeconds = currentDelay / 1000;
					showToast("WARP Retrying", "Next attempt in " + delaySeconds + "s...");

					try {
						Thread.sleep(currentDelay);
					} catch (InterruptedException e) {
						LOGGER.info("Retry sleep interrupted");
						tunnelConnecting = false;
						return;
					}

					// Exponential backoff: double the delay, up to max
					currentDelay = Math.min(currentDelay * 2, MAX_RETRY_DELAY_MS);
				}

			} catch (Exception e) {
				tunnelFailed = true;
				tunnelConnecting = false;
				failureReason = e.getMessage();
				LOGGER.error("Unexpected error starting WARP tunnel", e);
				showToast("WARP Connection Failed", e.getMessage());
			}
		}, "WireguardTunnel-Connect");
		connectThread.start();
	}

	/**
	 * Show a toast notification to the user.
	 *
	 * @param title   the toast title
	 * @param message the toast message
	 */
	private static void showToast(String title, String message) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client != null) {
			// Execute on main thread to avoid threading issues
			client.execute(() -> {
				if (client.getToasts() != null) {
					client.getToasts().addToast(new SystemToast(
							SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
							Component.literal(title),
							Component.literal(message)
					));
				}
			});
		}
	}
}
