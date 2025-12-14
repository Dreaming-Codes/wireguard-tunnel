package codes.dreaming.wireguard.netty;

import codes.dreaming.wireguard.jni.Native;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Netty Channel implementation that routes TCP traffic through
 * the WireGuard tunnel via JNI.
 * <p>
 * This channel delegates all I/O operations to the native library,
 * bypassing Java's standard socket implementation.
 */
public class WgSocketChannel extends AbstractChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger("wireguard-tunnel");

    private static final int READ_BUFFER_SIZE = 16384;
    private static final long CONNECT_TIMEOUT_MS = 30000;

    private final WgChannelConfig config;
    private final AtomicLong nativeHandle = new AtomicLong(-1);
    private final AtomicBoolean inputShutdown = new AtomicBoolean(false);
    private final AtomicBoolean outputShutdown = new AtomicBoolean(false);

    private volatile boolean active = false;
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;

    private volatile Thread readerThread;

    public WgSocketChannel() {
        super(null);
        this.config = new WgChannelConfig(this);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new WgUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        // We work with any event loop since we do our own I/O
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        // For client connections, we don't really bind
        this.localAddress = (InetSocketAddress) localAddress;
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        active = false;
        inputShutdown.set(true);
        outputShutdown.set(true);

        // Interrupt reader thread if running
        Thread reader = readerThread;
        if (reader != null) {
            reader.interrupt();
        }

        long handle = nativeHandle.getAndSet(-1);
        if (handle > 0) {
            try {
                Native.tcpClose(handle);
            } catch (Exception e) {
                LOGGER.debug("Error closing native handle: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown.get()) {
            return;
        }

        // Start reader thread if not already running
        if (readerThread == null || !readerThread.isAlive()) {
            LOGGER.debug("Starting reader thread for channel {}", id());
            readerThread = new Thread(this::readLoop, "WgSocketChannel-Reader-" + id());
            readerThread.setDaemon(true);
            readerThread.start();
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        long handle = nativeHandle.get();
        if (handle <= 0) {
            throw new ClosedChannelException();
        }

        while (true) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }

            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                if (readableBytes == 0) {
                    in.remove();
                    continue;
                }

                byte[] data = new byte[readableBytes];
                buf.readBytes(data);

                try {
                    int written = Native.tcpWrite(handle, data, 0, data.length);
                    LOGGER.debug("Wrote {} bytes to handle {}", written, handle);
                    if (written < 0) {
                        throw new IOException("Write failed");
                    }
                    in.remove();
                } catch (Exception e) {
                    LOGGER.error("Write error: {}", e.getMessage());
                    in.remove(e);
                    throw e;
                }
            } else {
                // Unknown message type
                in.remove(new UnsupportedOperationException(
                        "Unsupported message type: " + msg.getClass().getName()));
            }
        }

    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return nativeHandle.get() >= 0 || !inputShutdown.get();
    }

    @Override
    public boolean isActive() {
        return active && nativeHandle.get() > 0;
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(false);
    }

    /**
     * Read loop that runs in a separate thread, reading from the native
     * TCP connection and dispatching to the Netty pipeline.
     */
    private void readLoop() {
        LOGGER.debug("Read loop started for channel {}", id());
        byte[] buffer = new byte[READ_BUFFER_SIZE];

        try {
            while (!inputShutdown.get() && isActive()) {
                long handle = nativeHandle.get();
                if (handle <= 0) {
                    LOGGER.warn("Read loop: handle is invalid ({})", handle);
                    break;
                }

                int bytesRead;
                try {
                    LOGGER.debug("Waiting for data on handle {}", handle);
                    bytesRead = Native.tcpRead(handle, buffer);
                    LOGGER.debug("Read {} bytes from handle {}", bytesRead, handle);
                } catch (Exception e) {
                    if (!inputShutdown.get()) {
                        LOGGER.error("Read error: {}", e.getMessage());
                        final Exception exc = e;
                        eventLoop().execute(() -> pipeline().fireExceptionCaught(exc));
                    }
                    break;
                }

                if (bytesRead < 0) {
                    // Error
                    LOGGER.warn("Read returned error code: {}", bytesRead);
                    break;
                } else if (bytesRead == 0) {
                    // EOF
                    LOGGER.debug("Read returned EOF");
                    inputShutdown.set(true);
                    eventLoop().execute(() -> pipeline().fireUserEventTriggered(
                            ChannelInputShutdownEvent.INSTANCE));
                    break;
                } else {
                    // Data received - dispatch to event loop
                    ByteBuf data = alloc().buffer(bytesRead);
                    data.writeBytes(buffer, 0, bytesRead);

                    eventLoop().execute(() -> {
                        if (isActive()) {
                            pipeline().fireChannelRead(data);
                            pipeline().fireChannelReadComplete();
                        } else {
                            data.release();
                        }
                    });
                }
            }
        } finally {
            // Signal that reading has stopped
            if (!inputShutdown.get()) {
                inputShutdown.set(true);
                eventLoop().execute(() -> {
                    if (isOpen()) {
                        pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                    }
                });
            }
        }
    }

    /**
     * Custom Unsafe implementation for WgSocketChannel.
     */
    private class WgUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            if (!ensureOpen(promise)) {
                return;
            }

            try {
                if (!(remoteAddress instanceof InetSocketAddress)) {
                    promise.setFailure(new IllegalArgumentException(
                            "Expected InetSocketAddress, got: " + remoteAddress.getClass()));
                    return;
                }

                InetSocketAddress remote = (InetSocketAddress) remoteAddress;
                WgSocketChannel.this.remoteAddress = remote;

                if (localAddress instanceof InetSocketAddress) {
                    WgSocketChannel.this.localAddress = (InetSocketAddress) localAddress;
                }

                // Check tunnel is ready
                if (!Native.isTunnelReady()) {
                    promise.setFailure(new IOException(
                            "WireGuard tunnel not ready, state: " +
                            Native.tunnelStateToString(Native.tunnelState())));
                    return;
                }

                // Use the IP address, not hostname, since Rust SocketAddr doesn't do DNS
                String host = remote.getAddress() != null
                        ? remote.getAddress().getHostAddress()
                        : remote.getHostString();
                int port = remote.getPort();

                // Warn about Cloudflare-proxied servers (they block WARP connections)
                if (isCloudflareIP(host)) {
                    LOGGER.warn("Server {} appears to be behind Cloudflare Spectrum. " +
                            "Cloudflare blocks connections from WARP - connection may fail.", host);
                }

                LOGGER.debug("Connecting to {}:{} via WireGuard tunnel", host, port);

                // Perform connection in a separate thread to not block the event loop
                new Thread(() -> {
                    try {
                        long handle = Native.tcpConnect(host, port, CONNECT_TIMEOUT_MS);
                        if (handle <= 0) {
                            eventLoop().execute(() ->
                                    promise.setFailure(new IOException("Connection failed")));
                            return;
                        }

                        nativeHandle.set(handle);
                        active = true;

                        eventLoop().execute(() -> {
                            promise.setSuccess();
                            pipeline().fireChannelActive();
                        });

                        LOGGER.debug("Connected to {}:{} via WireGuard tunnel", host, port);
                    } catch (Exception e) {
                        eventLoop().execute(() -> promise.setFailure(e));
                    }
                }, "WgSocketChannel-Connect").start();

            } catch (Exception e) {
                promise.setFailure(e);
            }
        }
    }

    /**
     * Channel configuration for WgSocketChannel.
     */
    private static class WgChannelConfig extends DefaultChannelConfig {
        WgChannelConfig(Channel channel) {
            super(channel);
            // Set reasonable defaults
            setConnectTimeoutMillis((int) CONNECT_TIMEOUT_MS);
        }
    }

    /**
     * Check if an IP address belongs to Cloudflare's network.
     * Cloudflare blocks WARP connections to their Spectrum-proxied servers.
     * 
     * Common Cloudflare IP ranges used for Spectrum:
     * - 104.16.0.0/12 (104.16.x.x - 104.31.x.x)
     * - 172.64.0.0/13 (172.64.x.x - 172.71.x.x)
     * - 162.158.0.0/15
     * - 198.41.128.0/17
     */
    private static boolean isCloudflareIP(String ip) {
        if (ip == null) return false;
        
        // Simple prefix-based detection for common Cloudflare ranges
        return ip.startsWith("104.16.") || ip.startsWith("104.17.") || 
               ip.startsWith("104.18.") || ip.startsWith("104.19.") ||
               ip.startsWith("104.20.") || ip.startsWith("104.21.") ||
               ip.startsWith("104.22.") || ip.startsWith("104.23.") ||
               ip.startsWith("104.24.") || ip.startsWith("104.25.") ||
               ip.startsWith("104.26.") || ip.startsWith("104.27.") ||
               ip.startsWith("172.64.") || ip.startsWith("172.65.") ||
               ip.startsWith("172.66.") || ip.startsWith("172.67.") ||
               ip.startsWith("172.68.") || ip.startsWith("172.69.") ||
               ip.startsWith("172.70.") || ip.startsWith("172.71.") ||
               ip.startsWith("162.158.") || ip.startsWith("162.159.") ||
               ip.startsWith("198.41.");
    }
}
