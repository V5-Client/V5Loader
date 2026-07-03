package com.v5.mixins;

import com.chattriggers.ctjs.api.client.Proxy;
import com.chattriggers.ctjs.api.client.ProxyInfo;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.BandwidthDebugMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.util.List;

@Mixin(Connection.class)
public abstract class ConnectionProxyMixin {

    @Inject(method = "configureSerialization", at = @At("HEAD"))
    private static void addHandlers(
            ChannelPipeline pipeline,
            PacketFlow nwside,
            boolean singleplayer,
            BandwidthDebugMonitor packetSizeLogger,
            CallbackInfo ci
    ) {
        if (nwside != PacketFlow.CLIENTBOUND || singleplayer) return;

        List<Proxy> activeProxies = ProxyInfo.INSTANCE.getEnabledProxies();
        if (activeProxies.isEmpty()) return;

        Proxy proxy = activeProxies.getFirst();

        String ip = proxy.getIp();
        if (ip == null) return;
        ip = ip.trim();

        String username = proxy.getUsername();
        String password = proxy.getPassword();

        username = (username != null) ? username.trim() : "";
        password = (password != null) ? password.trim() : "";

        if (!username.isEmpty()) {
            pipeline.addFirst("proxy", new Socks5ProxyHandler(
                new InetSocketAddress(ip, proxy.getPort()),
                username,
                password
            ));
        } else {
            pipeline.addFirst("proxy", new Socks5ProxyHandler(
                new InetSocketAddress(ip, proxy.getPort())
            ));
        }
    }
}
