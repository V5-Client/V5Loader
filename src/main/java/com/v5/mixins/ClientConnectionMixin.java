package com.v5.mixins;

import com.v5.event.PacketEvent;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

  @Inject(method = "handlePacket", at = @At("HEAD"))
  private static void onPacketReceived(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
    if (packet instanceof BundleS2CPacket bundlePacket) {
      for (Packet<?> subPacket : bundlePacket.getPackets()) {
        PacketEvent.RECEIVE.invoker().trigger(subPacket);
      }
    } else {
      PacketEvent.RECEIVE.invoker().trigger(packet);
    }
  }
}
