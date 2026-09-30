package dev.champions.core.module.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ServerListPingTest {
	@Test void encodesVarIntBoundaries() {
		assertEqualsBytes(new byte[] {0x00}, varInt(0));
		assertEqualsBytes(new byte[] {0x7F}, varInt(127));
		assertEqualsBytes(new byte[] {(byte) 0x80, 0x01}, varInt(128));
		assertEqualsBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, 0x7F}, varInt(2097151));
		assertEqualsBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, varInt(-1));
	}

	@Test void buildsFramedStatusRequest() {
		assertEqualsBytes(new byte[] {0x01, 0x00}, ServerListPing.statusRequest());
	}

	@Test void buildsFramedHandshake() {
		byte[] packet = ServerListPing.handshake("mc.example.com", 25565, -1);
		byte[] expected = new byte[] {
			0x18, // frame length 24
			0x00, // packet id: handshake
			(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, // protocol -1
			0x0E, 'm', 'c', '.', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', // host (14 chars)
			0x63, (byte) 0xDD, // port 25565
			0x01 // next state: status
		};
		assertEqualsBytes(expected, packet);
	}

	@Test void readsFramedStatusPayload() throws IOException {
		ByteArrayOutputStream packet = new ByteArrayOutputStream();
		ServerListPing.writeVarInt(packet, 4); // frame length
		packet.write(0x00); // packet id: status response
		ServerListPing.writeVarInt(packet, 2); // string length
		packet.writeBytes("hi".getBytes(StandardCharsets.UTF_8));
		assertEquals("hi", ServerListPing.readStatusPayload(new ByteArrayInputStream(packet.toByteArray())));
	}

	@Test void readsFramedPong() throws IOException {
		ByteArrayOutputStream packet = new ByteArrayOutputStream();
		ServerListPing.writeVarInt(packet, 9);
		packet.write(0x01);
		for (byte b : new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88}) packet.write(b);
		assertEquals(0x1122334455667788L, ServerListPing.readPong(new ByteArrayInputStream(packet.toByteArray())));
	}

	@Test void rejectsUnexpectedPacketIds() {
		ByteArrayOutputStream status = new ByteArrayOutputStream();
		ServerListPing.writeVarInt(status, 9);
		status.write(0x01); // pong id where status expected
		status.writeBytes(new byte[8]);
		assertThrows(IOException.class, () -> ServerListPing.readStatusPayload(new ByteArrayInputStream(status.toByteArray())));

		ByteArrayOutputStream pong = new ByteArrayOutputStream();
		ServerListPing.writeVarInt(pong, 3);
		pong.write(0x00);
		pong.write(0x01);
		pong.write(0x02);
		assertThrows(IOException.class, () -> ServerListPing.readPong(new ByteArrayInputStream(pong.toByteArray())));
	}

	@Test void parsesAddresses() {
		assertEquals(new ServerListPing.EndPoint("play.example.com", 25565), ServerListPing.parseAddress("play.example.com"));
		assertEquals(new ServerListPing.EndPoint("play.example.com", 25566), ServerListPing.parseAddress("play.example.com:25566"));
		assertEquals(new ServerListPing.EndPoint("::1", 25565), ServerListPing.parseAddress("[::1]:25565"));
		assertNull(ServerListPing.parseAddress("host:99999"));
		assertNull(ServerListPing.parseAddress("host:0"));
		assertNull(ServerListPing.parseAddress("[::1]garbage"));
		assertNull(ServerListPing.parseAddress("singleplayer"));
		assertNull(ServerListPing.parseAddress("unknown"));
		assertNull(ServerListPing.parseAddress("  "));
		assertNull(ServerListPing.parseAddress(null));
	}

	private static byte[] varInt(int value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ServerListPing.writeVarInt(out, value);
		return out.toByteArray();
	}

	private static void assertEqualsBytes(byte[] expected, byte[] actual) {
		assertArrayEquals(expected, actual);
	}
}
