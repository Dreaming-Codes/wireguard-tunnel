package codes.dreaming.wireguard.mixin.client;

import codes.dreaming.wireguard.WireguardConfig;
import codes.dreaming.wireguard.WireguardTunnelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to add a WARP toggle button to the main options/settings screen.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    @Unique
    private Button wireguard_tunnel$warpButton;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    /**
     * Inject into the init method to add our WARP toggle button.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void wireguard_tunnel$addWarpButton(CallbackInfo ci) {
        int buttonWidth = 60;
        int buttonHeight = 20;
        // Position in the top-left corner with some padding
        int x = 8;
        int y = 8;

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

        // Disable the button if we're in a game (connected to a world/server)
        this.wireguard_tunnel$warpButton.active = Minecraft.getInstance().level == null;

        this.addRenderableWidget(this.wireguard_tunnel$warpButton);
    }

    @Unique
    private Component wireguard_tunnel$getButtonText() {
        boolean enabled = WireguardConfig.getInstance().isWarpEnabled();
        return Component.literal("WARP: " + (enabled ? "ON" : "OFF"));
    }
}
