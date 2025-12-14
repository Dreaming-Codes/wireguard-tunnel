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

    // ========================================================================
    // Tunnel state constants
    // ========================================================================

    /** Tunnel is stopped/not initialized */
    public static final int TUNNEL_STATE_STOPPED = 0;
    /** Tunnel is starting up */
    public static final int TUNNEL_STATE_STARTING = 1;
    /** Tunnel is ready and accepting connections */
    public static final int TUNNEL_STATE_READY = 2;
    /** Tunnel failed to start or encountered an error */
    public static final int TUNNEL_STATE_FAILED = 3;

    // ========================================================================
    // Initialization
    // ========================================================================

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

    // ========================================================================
    // Tunnel Lifecycle
    // ========================================================================

    /**
     * Start the WARP tunnel.
     * <p>
     * This will load or generate WARP credentials and establish the tunnel.
     * The credentials are persisted to the specified path for reuse.
     *
     * @param credPath path to store/load WARP credentials JSON file
     * @return tunnel state after starting (TUNNEL_STATE_READY on success)
     * @throws RuntimeException if tunnel fails to start
     */
    public static native int startWarpTunnel(String credPath);

    /**
     * Get the current tunnel state.
     *
     * @return one of TUNNEL_STATE_* constants
     */
    public static native int tunnelState();

    /**
     * Shutdown the tunnel.
     * <p>
     * This closes all active connections and stops the tunnel.
     */
    public static native void shutdownTunnel();

    // ========================================================================
    // TCP Operations
    // ========================================================================

    /**
     * Connect to a remote host via the tunnel.
     * <p>
     * This performs DNS resolution through the tunnel and establishes
     * a TCP connection to the resolved address.
     *
     * @param host      hostname or IP address to connect to
     * @param port      port number (1-65535)
     * @param timeoutMs connection timeout in milliseconds (0 for no timeout)
     * @return connection handle (positive value) on success
     * @throws RuntimeException if connection fails or tunnel not ready
     */
    public static native long tcpConnect(String host, int port, long timeoutMs);

    /**
     * Read data from a TCP connection.
     * <p>
     * This is a blocking call that waits for data to be available.
     *
     * @param handle connection handle from {@link #tcpConnect}
     * @param buffer byte array to read data into
     * @return number of bytes read, 0 on EOF
     * @throws RuntimeException on read error or invalid handle
     */
    public static native int tcpRead(long handle, byte[] buffer);

    /**
     * Write data to a TCP connection.
     *
     * @param handle connection handle from {@link #tcpConnect}
     * @param data   byte array containing data to write
     * @param offset offset in the array to start writing from
     * @param length number of bytes to write
     * @return number of bytes written
     * @throws RuntimeException on write error or invalid handle
     */
    public static native int tcpWrite(long handle, byte[] data, int offset, int length);

    /**
     * Close a TCP connection.
     * <p>
     * After calling this, the handle is no longer valid.
     *
     * @param handle connection handle from {@link #tcpConnect}
     */
    public static native void tcpClose(long handle);

    /**
     * Flush a TCP connection.
     * <p>
     * Ensures all buffered data is sent.
     *
     * @param handle connection handle from {@link #tcpConnect}
     * @return 0 on success
     * @throws RuntimeException on flush error or invalid handle
     */
    public static native int tcpFlush(long handle);

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Check if the tunnel is ready for connections.
     *
     * @return true if tunnel state is TUNNEL_STATE_READY
     */
    public static boolean isTunnelReady() {
        return tunnelState() == TUNNEL_STATE_READY;
    }

    /**
     * Get a human-readable description of the tunnel state.
     *
     * @param state tunnel state value
     * @return description string
     */
    public static String tunnelStateToString(int state) {
        switch (state) {
            case TUNNEL_STATE_STOPPED:
                return "STOPPED";
            case TUNNEL_STATE_STARTING:
                return "STARTING";
            case TUNNEL_STATE_READY:
                return "READY";
            case TUNNEL_STATE_FAILED:
                return "FAILED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
