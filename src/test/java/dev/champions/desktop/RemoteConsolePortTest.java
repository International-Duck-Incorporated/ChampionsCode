package dev.champions.desktop;

import dev.champions.net.ipc.Protocol;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteConsolePortTest {
	@Test void rejectedHandshakeClosesTheClientSocket() throws Exception {
		assertConnectionClosedAfterReply(Protocol.encodeError("invalid token"), java.io.IOException.class);
	}

	@Test void malformedHandshakeClosesTheClientSocket() throws Exception {
		assertConnectionClosedAfterReply("not-json", IllegalArgumentException.class);
	}

	private static void assertConnectionClosedAfterReply(String reply, Class<? extends Exception> failure) throws Exception {
		try (ServerSocket server = new ServerSocket()) {
			server.bind(new InetSocketAddress("127.0.0.1", 0));
			server.setSoTimeout(3_000);
			var executor = Executors.newSingleThreadExecutor();
			try {
				var observedClose = executor.submit(() -> {
					try (Socket socket = server.accept()) {
						socket.setSoTimeout(3_000);
						BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
						assertEquals(Protocol.HELLO, Protocol.type(Protocol.decode(input.readLine())));
						socket.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
						socket.getOutputStream().flush();
						try {
							return input.read() == -1;
						} catch (SocketTimeoutException leaked) {
							return false;
						}
					}
				});
				assertThrows(failure, () -> RemoteConsolePort.connect("127.0.0.1", server.getLocalPort(), "token", "test"));
				assertTrue(observedClose.get(5, TimeUnit.SECONDS), "failed handshake left its socket open");
			} finally {
				executor.shutdownNow();
			}
		}
	}
}
