package dev.champions.core.module.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Minimal implementation of the Minecraft server-list status ping (handshake
 * with next state "status", status request, optional ping/pong round trip).
 * The protocol has been stable across versions and lets a client read what any
 * server-list entry or scanner site can see without joining. Only ever invoked
 * with a single, explicitly authorized host and port; there is deliberately no
 * range or sweep support.
 */
public final class ServerListPing {
	private ServerListPing() {}

	/** One completed status exchange: the JSON document and the ping/pong round trip. */
	public record Reply(long rttMillis, String json) {}

	/** A host and port parsed from a Minecraft server address string. */
	public record EndPoint(String host, int port) {}

	/** Performs one bounded status ping against a single endpoint. */
	public static Reply contact(String host, int port, int timeoutMillis) throws IOException {
		long start = System.nanoTime();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMillis);
			socket.setSoTimeout(timeoutMillis);
			socket.getOutputStream().write(handshake(host, port, -1));
			socket.getOutputStream().write(statusRequest());
			String json = readStatusPayload(socket.getInputStream());
			long rtt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			return new Reply(rtt, json);
		}
	}

	/** Parses "host", "host:port", or "[v6]:port"; returns null when not a remote address. */
	public static EndPoint parseAddress(String address) {
		if (address == null) return null;
		String trimmed = address.strip();
		if (trimmed.isEmpty() || "singleplayer".equalsIgnoreCase(trimmed) || "unknown".equalsIgnoreCase(trimmed)) return null;
		if (trimmed.startsWith("[")) {
			int close = trimmed.indexOf(']');
			if (close < 0) return null;
			String host = trimmed.substring(1, close);
			if (host.isBlank()) return null;
			String rest = trimmed.substring(close + 1);
			if (rest.isEmpty()) return new EndPoint(host, 25565);
			if (rest.startsWith(":") && rest.substring(1).matches("[0-9]{1,5}")) {
				int port = parsePort(rest.substring(1));
				return port == -1 ? null : new EndPoint(host, port);
			}
			return null;
		}
		int colon = trimmed.lastIndexOf(':');
		if (colon > 0 && trimmed.indexOf(':') == colon && trimmed.substring(colon + 1).matches("[0-9]{1,5}")) {
			int port = parsePort(trimmed.substring(colon + 1));
			return port == -1 ? null : new EndPoint(trimmed.substring(0, colon), port);
		}
		return new EndPoint(trimmed, 25565);
	}

	private static int parsePort(String raw) {
		try {
			int port = Integer.parseInt(raw);
			return port >= 1 && port <= 65535 ? port : -1;
		} catch (NumberFormatException invalid) {
			return -1;
		}
	}

	/** Builds the framed handshake packet switching the connection to status mode. */
	public static byte[] handshake(String host, int port, int protocolVersion) {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(0x00);
		writeVarInt(payload, protocolVersion);
		writeString(payload, host);
		payload.write((port >>> 8) & 0xFF);
		payload.write(port & 0xFF);
		writeVarInt(payload, 1); // next state: status
		return frame(payload.toByteArray());
	}

	/** Builds the framed, empty status request packet. */
	public static byte[] statusRequest() {
		return frame(new byte[] {0x00});
	}

	/** Builds the framed ping packet carrying an arbitrary long. */
	public static byte[] ping(long payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x01);
		for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (payload >>> shift) & 0xFF);
		return frame(out.toByteArray());
	}

	/** Reads one framed packet and returns the status JSON string it carries. */
	public static String readStatusPayload(InputStream in) throws IOException {
		byte[] packet = readPacket(in);
		if (packet.length < 1 || packet[0] != 0x00) throw new IOException("unexpected packet id in status reply");
		int[] cursor = {1};
		int length = readVarInt(packet, cursor);
		if (length < 0 || cursor[0] + length > packet.length) throw new IOException("truncated status string");
		return new String(packet, cursor[0], length, StandardCharsets.UTF_8);
	}

	/** Reads one framed packet and returns the pong payload long. */
	public static long readPong(InputStream in) throws IOException {
		byte[] packet = readPacket(in);
		if (packet.length != 9 || packet[0] != 0x01) throw new IOException("unexpected packet id in pong reply");
		long value = 0;
		for (int i = 1; i <= 8; i++) value = (value << 8) | (packet[i] & 0xFF);
		return value;
	}

	private static byte[] readPacket(InputStream in) throws IOException {
		int length = readVarInt(in);
		if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("unreasonable status packet length: " + length);
		byte[] packet = new byte[length];
		int read = 0;
		while (read < length) {
			int chunk = in.read(packet, read, length - read);
			if (chunk < 0) throw new IOException("status stream ended early");
			read += chunk;
		}
		return packet;
	}

	private static byte[] frame(byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeVarInt(out, payload.length);
		out.writeBytes(payload);
		return out.toByteArray();
	}

	static void writeVarInt(ByteArrayOutputStream out, int value) {
		long working = value & 0xFFFFFFFFL;
		while (true) {
			if ((working & ~0x7FL) == 0) {
				out.write((int) working);
				return;
			}
			out.write((int) ((working & 0x7F) | 0x80));
			working >>>= 7;
		}
	}

	static void writeString(ByteArrayOutputStream out, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.writeBytes(bytes);
	}

	static int readVarInt(InputStream in) throws IOException {
		int value = 0;
		for (int position = 0; position < 5; position++) {
			int read = in.read();
			if (read < 0) throw new IOException("stream ended inside a VarInt");
			value |= (read & 0x7F) << (7 * position);
			if ((read & 0x80) == 0) return value;
		}
		throw new IOException("VarInt longer than 5 bytes");
	}

	private static int readVarInt(byte[] packet, int[] cursor) throws IOException {
		int value = 0;
		for (int position = 0; position < 5; position++) {
			if (cursor[0] >= packet.length) throw new IOException("packet ended inside a VarInt");
			int read = packet[cursor[0]++];
			value |= (read & 0x7F) << (7 * position);
			if ((read & 0x80) == 0) return value;
		}
		throw new IOException("VarInt longer than 5 bytes");
	}
}
