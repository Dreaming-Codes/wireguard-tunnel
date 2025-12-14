package codes.dreaming.wireguard.mixin.client;

import codes.dreaming.wireguard.WireguardConfig;
import codes.dreaming.wireguard.WireguardTunnelClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to add a WARP toggle button to the multiplayer server list screen.
 * The button is placed next to the "Add Server" button.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    @Unique
    private Button wireguard_tunnel$warpButton;

    protected MultiplayerScreenMixin(Component title) {
        super(title);
    }

    /**
     * Inject into the init method to add our WARP toggle button.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void wireguard_tunnel$addWarpButton(CallbackInfo ci) {
        // The Add Server button is typically at the bottom left area
        // We'll add our button to the left of it
        // Standard button dimensions: width=100, height=20
        // Add Server button is usually at: x = this.width / 2 - 154, y = this.height - 52

        int buttonWidth = 60;
        int buttonHeight = 20;
        // Position to the left of the Add Server button
        int x = this.width / 2 - 154 - buttonWidth - 4;
        int y = this.height - 52;

        this.wireguard_tunnel$warpButton = new Button(
                x, y, buttonWidth, buttonHeight,
                wireguard_tunnel$getButtonText(),
                button -> {
                    boolean nowEnabled = WireguardConfig.getInstance().toggleWarp();
                    button.setMessage(wireguard_tunnel$getButtonText());
                    // Start tunnel if just enabled
                    if (nowEnabled) {
                        WireguardTunnelClient.startTunnelWithFeedback();
                    }
                }
        );

        this.addRenderableWidget(this.wireguard_tunnel$warpButton);
    }

    @Unique
    private Component wireguard_tunnel$getButtonText() {
        boolean enabled = WireguardConfig.getInstance().isWarpEnabled();
        return Component.literal("WARP: " + (enabled ? "ON" : "OFF"));
    }
}
