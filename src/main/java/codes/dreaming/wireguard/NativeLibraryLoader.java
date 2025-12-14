package codes.dreaming.wireguard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads native libraries for the WireGuard tunnel mod.
 * <p>
 * Supports loading from:
 * - Development environment: ../build/natives/{targetDir}/{libName}
 * - Production (JAR): /natives/{targetDir}/{libName}
 * <p>
 * Supported platforms:
 * - x86_64-linux, aarch64-linux
 * - x86_64-windows
 * - x86_64-macos, aarch64-macos
 */
public class NativeLibraryLoader {

    private static final String NATIVE_RESOURCE_PREFIX = "/natives/";

    /**
     * Load a native library by base name.
     *
     * @param baseName the base name of the library (e.g., "wireguard_tunnel_jni")
     */
    public static void loadLibrary(String baseName) {
        String targetDir = getTargetDir();
        String mappedName = mapLibraryName(baseName);

        // Try development environment first
        if (isDevelopmentEnvironment()) {
            File devLibFile = new File("../build/natives/" + targetDir + "/" + mappedName);
            if (devLibFile.exists()) {
                System.load(devLibFile.getAbsolutePath());
                return;
            }
        }

        // Load from JAR resource
        String resourcePath = NATIVE_RESOURCE_PREFIX + targetDir + "/" + mappedName;
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Native library not found in JAR: " + resourcePath);
            }

            // Extract to temp file and load
            String prefix = baseName + "_";
            String suffix = getLibrarySuffix();
            File tempFile = File.createTempFile(prefix, suffix);
            tempFile.deleteOnExit();
            Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load native library: " + resourcePath, e);
        }
    }

    /**
     * Get the target directory name for the current platform.
     *
     * @return e.g., "x86_64-linux", "aarch64-macos", "x86_64-windows"
     */
    private static String getTargetDir() {
        String arch = normalizeArch(System.getProperty("os.arch", "").toLowerCase(Locale.ROOT));
        String os = normalizeOs(System.getProperty("os.name", "").toLowerCase(Locale.ROOT));
        return arch + "-" + os;
    }

    /**
     * Normalize architecture string to our standard names.
     */
    private static String normalizeArch(String arch) {
        // x86_64 variants
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            return "x86_64";
        }
        // aarch64 variants
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        // Fallback (may not be supported)
        return arch;
    }

    /**
     * Normalize OS string to our standard names.
     */
    private static String normalizeOs(String os) {
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        // Fallback (may not be supported)
        return os;
    }

    /**
     * Map a base library name to the platform-specific filename.
     * Similar to System.mapLibraryName but we control the exact format.
     */
    private static String mapLibraryName(String baseName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return baseName + ".dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + baseName + ".dylib";
        } else {
            // Linux and others
            return "lib" + baseName + ".so";
        }
    }

    /**
     * Get the library file suffix for the current platform.
     */
    private static String getLibrarySuffix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return ".dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return ".dylib";
        } else {
            return ".so";
        }
    }

    /**
     * Check if running in Fabric development environment.
     */
    private static boolean isDevelopmentEnvironment() {
        String devEnv = System.getProperty("fabric.development", "false");
        return devEnv.equalsIgnoreCase("true");
    }
}
