package com.internationalduck.championscode.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

public class BoundaryValidationTestModule {

    public void testInvalidMovementValidation() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;

        Vec3d currentPos = client.player.getPos();
        
        // Simulates an invalid packet offset to test server bounds enforcement
        double offsetY = 100.0; 

        PlayerMoveC2SPacket.Full invalidPacket = new PlayerMoveC2SPacket.Full(
            currentPos.x,
            currentPos.y + offsetY,
            currentPos.z,
            client.player.getYaw(),
            client.player.getPitch(),
            client.player.isOnGround()
        );

        client.getNetworkHandler().sendPacket(invalidPacket);
