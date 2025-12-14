package codes.dreaming.wireguard.mixin.client;

import codes.dreaming.wireguard.WireguardConfig;
import codes.dreaming.wireguard.netty.WgSocketChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;

/**
 * Mixin to intercept Connection.connect() and route traffic through
 * the WireGuard tunnel.
 * <p>
 * This replaces the standard NioSocketChannel with our custom WgSocketChannel
 * that uses the native WireGuard tunnel for all I/O operations.
 */
@Mixin(Connection.class)
public class ClientConnectionMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("wireguard-tunnel");

    @Unique
    private static EventLoopGroup wireguard_tunnel$eventLoopGroup;

    /**
     * Intercept the connectToServer method to use our WireGuard tunnel channel.
     * We inject at HEAD and cancel the original method, providing our own implementation.
     */
    @Inject(method = "connectToServer", at = @At("HEAD"), cancellable = true)
    private static void wireguard_tunnel$connectToServer(
            InetSocketAddress address,
            boolean useEpoll,
            CallbackInfoReturnable<Connection> cir
    ) {
        // Skip WARP routing if disabled
        if (!WireguardConfig.getInstance().isWarpEnabled()) {
            LOGGER.info("WARP disabled, using direct connection to {}", address);
            return;
        }

        LOGGER.info("Intercepting connection to {} via WireGuard tunnel", address);

        // Create or reuse our event loop group
        if (wireguard_tunnel$eventLoopGroup == null) {
            wireguard_tunnel$eventLoopGroup = new DefaultEventLoopGroup();
        }

        final Connection connection = new Connection(PacketFlow.CLIENTBOUND);

        Bootstrap bootstrap = new Bootstrap()
                .group(wireguard_tunnel$eventLoopGroup)
                .channel(WgSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        try {
                            ch.config().setOption(ChannelOption.TCP_NODELAY, true);
                        } catch (ChannelException e) {
                            // Ignore - our channel may not support all options
                        }

                        // Set the protocol attribute that Minecraft expects
                        ch.attr(Connection.ATTRIBUTE_PROTOCOL).set(ConnectionProtocol.HANDSHAKING);

                        // Add the same handlers that Minecraft uses for network connections
                        ch.pipeline()
                                .addLast("timeout", new ReadTimeoutHandler(30))
                                .addLast("splitter", new Varint21FrameDecoder())
                                .addLast("decoder", new PacketDecoder(PacketFlow.CLIENTBOUND))
                                .addLast("prepender", new Varint21LengthFieldPrepender())
                                .addLast("encoder", new PacketEncoder(PacketFlow.SERVERBOUND))
                                .addLast("packet_handler", connection);
                    }
                });

        try {
            ChannelFuture channelFuture = bootstrap.connect(address).syncUninterruptibly();
            cir.setReturnValue(connection);
            LOGGER.info("Connected to {} via WireGuard tunnel", address);
        } catch (Exception e) {
            LOGGER.error("Failed to connect via WireGuard tunnel: {}", e.getMessage());
            throw new RuntimeException("WireGuard tunnel connection failed", e);
        }
    }
}
