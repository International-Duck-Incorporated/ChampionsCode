package com.internationalduck.championscode.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class PacketRateLimitTestModule {
    private boolean active = false;
    private int packetsSent = 0;
    private long startTime = 0;

    public void startTest(int targetPacketCount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;

        this.active = true;
        this.packetsSent = 0;
        this.startTime = System.currentTimeMillis();

        for (int i = 0; i < targetPacketCount; i++) {
            if (!active) break;
            
            client.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.OnGroundOnly(client.player.isOnGround())
            );
            packetsSent++;
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("[SecurityTest] Sent " + packetsSent + " packets in " + duration + " ms.");
        this.active = false;
    }

    public void stopTest() {
        this.active = false;
    }
