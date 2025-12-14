package codes.dreaming.wireguard;

import codes.dreaming.wireguard.jni.Native;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WireguardTunnelClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("wireguard-tunnel-client");

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
			throw new RuntimeException("Failed to load wireguard-tunnel native library", t);
		}
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info("WireGuard Tunnel client initialized");
	}
}
