package com.v5.mixins;

import com.v5.proxy.Proxy;
import com.v5.proxy.ProxyInfo;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.handler.PacketSizeLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.util.List;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionProxyMixin {

    @Inject(method = "addHandlers", at = @At("HEAD"))
    private static void addHandlers(
            ChannelPipeline pipeline,
            NetworkSide nwside,
            boolean singleplayer,
            PacketSizeLogger packetSizeLogger,
            CallbackInfo ci
    ) {
        if (nwside != NetworkSide.CLIENTBOUND || singleplayer) return;

        List<Proxy> activeProxies = ProxyInfo.INSTANCE.getEnabledProxies();
        if (activeProxies.isEmpty()) return;

        Proxy proxy = activeProxies.getFirst();

        String username = proxy.getUsername();
        String password = proxy.getPassword();

        if (username != null && !username.isBlank()) {
            pipeline.addFirst(
                    new Socks5ProxyHandler(
                            new InetSocketAddress(proxy.getIp(), proxy.getPort()),
                            username,
                            password
                    )
            );
        } else {
            pipeline.addFirst(
                    new Socks5ProxyHandler(
                            new InetSocketAddress(proxy.getIp(), proxy.getPort())
                    )
            );
        }
    }
}