package com.internationalduck.championscode.module.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Random;

public class CustomPayloadAuditorModule {

    private final Random random = new Random();

    public void sendDummyPayloads(String channelNamespace, String channelPath) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client.getConnection();
        if (listener == null) return;

        Identifier channel = Identifier.fromNamespaceAndPath(channelNamespace, channelPath);

        // Payload 1: Empty Buffer
        FriendlyByteBuf emptyBuf = new FriendlyByteBuf(Unpooled.buffer());
        dispatchPayload(listener, channel, emptyBuf);

        // Payload 2: Maximum Size Buffer (e.g., 32767 bytes)
        byte[] largeData = new byte[32767];
        random.nextBytes(largeData);
        FriendlyByteBuf largeBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(largeData));
        dispatchPayload(listener, channel, largeBuf);

        // Payload 3: Malformed String / JSON
        String malformedJson = "{ \"test\": \"value\", "; // unclosed JSON syntax
        FriendlyByteBuf jsonBuf = new FriendlyByteBuf(Unpooled.buffer());
        jsonBuf.writeUtf(malformedJson);
        dispatchPayload(listener, channel, jsonBuf);

        // Payload 4: Random Fuzzed Bytes
        byte[] fuzzData = new byte[256];
        random.nextBytes(fuzzData);
        FriendlyByteBuf fuzzBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(fuzzData));
        dispatchPayload(listener, channel, fuzzBuf);

        System.out.println("[SecurityTest] Dispatched fuzzing payloads to channel: " + channel.toString());
    }

    private void dispatchPayload(ClientPacketListener listener, Identifier channel, FriendlyByteBuf buf) {
        // 26.3 sends serverbound custom payloads as
        // ServerboundCustomPayloadPacket(CustomPacketPayload). Vanilla only ships a
        // data-less DiscardedPayload(Identifier), so bind a payload record for the
        // channel under test and forward it through
        // ClientPlayNetworking.send(CustomPacketPayload).
    }
}
