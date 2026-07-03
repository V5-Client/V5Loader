package com.v5.mixins;

import com.chattriggers.ctjs.api.triggers.PacketEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {

  @Inject(method = "genericsFtw", at = @At("HEAD"))
  private static void onPacketReceived(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
    if (packet instanceof ClientboundBundlePacket bundlePacket) {
      for (Packet<?> subPacket : bundlePacket.subPackets()) {
        PacketEvent.RECEIVE.invoker().trigger(subPacket);
      }
    } else {
      PacketEvent.RECEIVE.invoker().trigger(packet);
    }
  }
}
