package codes.dreaming.wireguard.jni;

/**
 * JNI bridge to the native WireGuard tunnel library.
 * <p>
 * Before calling any methods, ensure the native library is loaded via
 * {@link codes.dreaming.wireguard.NativeLibraryLoader#loadLibrary(String)}.
 */
public final class Native {

    private Native() {
        // Prevent instantiation
    }

    /**
     * Initialize the JNI layer.
     * <p>
     * Must be called once after loading the native library,
     * before calling any other native methods.
     */
    public static native void initJNI();

    /**
     * Simple ping to verify the native library is loaded and working.
     *
     * @return a confirmation string from the native side
     */
    public static native String ping();

    /**
     * Get the version of the native library.
     *
     * @return the version string (e.g., "0.1.0")
     */
    public static native String version();
}
