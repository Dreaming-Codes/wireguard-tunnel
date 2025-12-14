package codes.dreaming.wireguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration manager for WireGuard Tunnel mod.
 * <p>
 * Handles loading and saving of mod configuration including the WARP tunnel toggle state.
 * Configuration is persisted to a JSON file in the game's config directory.
 */
public class WireguardConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("wireguard-tunnel");
    private static final String CONFIG_FILE = "wireguard-tunnel/config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static WireguardConfig instance;

    /**
     * Whether the WARP tunnel is enabled.
     * When enabled, all server connections are routed through the WARP tunnel.
     */
    private boolean warpEnabled = true;

    private WireguardConfig() {
        // Private constructor - use getInstance()
    }

    /**
     * Get the singleton config instance.
     * Loads from disk on first access.
     *
     * @return the config instance
     */
    public static WireguardConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Check if WARP tunnel is enabled.
     *
     * @return true if WARP is enabled
     */
    public boolean isWarpEnabled() {
        return warpEnabled;
    }

    /**
     * Set whether WARP tunnel is enabled.
     * Automatically saves the config to disk.
     *
     * @param enabled true to enable WARP
     */
    public void setWarpEnabled(boolean enabled) {
        this.warpEnabled = enabled;
        save();
    }

    /**
     * Toggle the WARP tunnel state.
     * Automatically saves the config to disk.
     *
     * @return the new state (true if now enabled)
     */
    public boolean toggleWarp() {
        this.warpEnabled = !this.warpEnabled;
        save();
        return this.warpEnabled;
    }

    /**
     * Get the config file path.
     *
     * @return path to the config file
     */
    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }

    /**
     * Load config from disk, or create default if not exists.
     *
     * @return the loaded or default config
     */
    private static WireguardConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                WireguardConfig config = GSON.fromJson(json, WireguardConfig.class);
                if (config != null) {
                    LOGGER.info("Loaded config: warpEnabled={}", config.warpEnabled);
                    return config;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load config, using defaults", e);
            }
        }

        // Create default config
        WireguardConfig config = new WireguardConfig();
        config.save();
        return config;
    }

    /**
     * Save config to disk.
     */
    public void save() {
        Path configPath = getConfigPath();

        try {
            // Ensure parent directory exists
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            LOGGER.info("Saved config: warpEnabled={}", warpEnabled);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }
}
