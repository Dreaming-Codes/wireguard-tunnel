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

	private static boolean tunnelReady = false;
	private static boolean tunnelFailed = false;
	private static String failureReason = null;
	private static boolean toastShown = false;

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
		if (tunnelReady) {
			LOGGER.info("Shutting down WARP tunnel...");
			Native.shutdownTunnel();
			tunnelReady = false;
			LOGGER.info("WARP tunnel shut down");
		}
	}
}
