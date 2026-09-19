package dev.champions.client.mixin;

import dev.champions.client.PacketTelemetry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records packet class/direction/timing metadata only; never payload contents. */
@Mixin(Connection.class)
abstract class ChampionsConnectionMixin {
	@Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void champions$observeOutbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		PacketTelemetry.outbound(packet);
	}

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void champions$observeInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		PacketTelemetry.inbound(packet);
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void champions$observeConnectionError(ChannelHandlerContext context, Throwable error, CallbackInfo ci) {
		PacketTelemetry.connectionError(error);
	}
}
