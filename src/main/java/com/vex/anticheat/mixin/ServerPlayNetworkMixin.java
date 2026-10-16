package com.vex.anticheat.mixin;

import com.vex.anticheat.PacketHandler;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayNetworkMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void vex$onMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        PacketHandler.onMove(this.player, packet);
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void vex$onInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (PacketHandler.onInteract(this.player, packet)) {
            ci.cancel();
        }
    }
}
