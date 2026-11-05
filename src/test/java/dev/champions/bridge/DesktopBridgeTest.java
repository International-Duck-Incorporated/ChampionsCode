package dev.champions.bridge;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.module.ModuleContext;
import dev.champions.desktop.RemoteConsolePort;
import dev.champions.net.ipc.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeTest {
	@TempDir Path temporary;

	@Test void replacesAStaleSessionSymlinkWithoutOverwritingItsTarget() throws Exception {
		Path target = temporary.resolve("unrelated.txt");
		Path session = temporary.resolve("desktop-session.json");
		Files.writeString(target, "untouched");
		try {
			Files.createSymbolicLink(session, target.getFileName());
		} catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unavailable) {
			org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unavailable.getMessage());
		}
		try (DesktopBridge bridge = startBridge()) {
			assertEquals("untouched", Files.readString(target));
			assertFalse(Files.isSymbolicLink(session));
			if (Files.getFileStore(session).supportsFileAttributeView("posix")) {
				assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(session)));
			}
		}
	}

	@Test void failedStartReleasesThePortWithoutRemovingTheExistingPath() throws Exception {
		Path existing = temporary.resolve("desktop-session.json");
		Files.createDirectory(existing);
		DesktopBridge bridge = createBridge(() -> "test");
		int port = bridge.port();
		try {
			assertThrows(java.io.IOException.class, bridge::start);
			try (ServerSocket probe = new ServerSocket()) {
				probe.bind(new InetSocketAddress("127.0.0.1", port));
			}
			assertTrue(Files.isDirectory(existing));
		} finally {
			bridge.close();
		}
		assertTrue(Files.isDirectory(existing));
	}

	@Test void acceptsIpv4ConnectionsAndRejectsAnInvalidToken() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			send(socket, Protocol.encodeHello("wrong-token", "test") + "\n");
			Map<String, Object> reply = Protocol.decode(reader(socket).readLine());
			assertEquals(Protocol.ERROR, Protocol.type(reply));
			assertEquals("invalid token", Protocol.str(reply, "message", ""));
			assertFalse(bridge.isAttached());
		}
	}

	@Test void realDesktopClientAttachesSubmitsAndReceivesConsoleOutput() throws Exception {
		try (DesktopBridge bridge = startBridge();
			 RemoteConsolePort client = RemoteConsolePort.connect("127.0.0.1", bridge.port(), bridge.token(), "test")) {
			assertEquals("test", client.modVersion());
			assertEquals("ATTACHED", client.mode());
			client.submit("unknown_regression_command");
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && client.buffer().snapshot().stream()
				.noneMatch(line -> line.text().contains("Unknown command: unknown_regression_command"))) {
				Thread.sleep(10);
			}
			assertTrue(client.buffer().snapshot().stream()
				.anyMatch(line -> line.text().contains("Unknown command: unknown_regression_command")));
			bridge.close();
			deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && !"DETACHED".equals(client.mode())) Thread.sleep(10);
			assertEquals("DETACHED", client.mode());
		}
	}

	@Test void rejectsOversizedHandshakeBeforeThePeerSendsANewline() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			byte[] oversized = new byte[DesktopBridge.MAX_FRAME_BYTES + 1];
			Arrays.fill(oversized, (byte) 'x');
			socket.getOutputStream().write(oversized);
			socket.getOutputStream().flush();
			Map<String, Object> reply = Protocol.decode(reader(socket).readLine());
			assertEquals(Protocol.ERROR, Protocol.type(reply));
			assertEquals("frame too large", Protocol.str(reply, "message", ""));
		}
	}

	@Test void enforcesFrameLimitInUtf8BytesNotJavaCharacters() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			String hello = "{\"t\":\"hello\",\"proto\":1,\"token\":\"" + bridge.token()
				+ "\",\"app\":\"" + "é".repeat(DesktopBridge.MAX_FRAME_BYTES / 2) + "\"}";
			assertTrue(hello.length() < DesktopBridge.MAX_FRAME_BYTES);
			assertTrue(hello.getBytes(StandardCharsets.UTF_8).length > DesktopBridge.MAX_FRAME_BYTES);
			send(socket, hello + "\n");
			Map<String, Object> reply = Protocol.decode(reader(socket).readLine());
			assertEquals(Protocol.ERROR, Protocol.type(reply));
			assertEquals("frame too large", Protocol.str(reply, "message", ""));
		}
	}

	@Test void acceptsExactlyTheFrameLimitWithCrLf() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			String hello = Protocol.encodeHello(bridge.token(), "test");
			int padding = DesktopBridge.MAX_FRAME_BYTES - hello.getBytes(StandardCharsets.UTF_8).length;
			send(socket, hello + " ".repeat(padding) + "\r\n");
			Map<String, Object> reply = Protocol.decode(reader(socket).readLine());
			assertEquals(Protocol.HELLO_OK, Protocol.type(reply));
		}
	}

	@Test void rejectsOversizedAuthenticatedFramesWithoutWaitingForANewline() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			BufferedReader reader = reader(socket);
			send(socket, Protocol.encodeHello(bridge.token(), "test") + "\n");
			assertEquals(Protocol.HELLO_OK, Protocol.type(Protocol.decode(reader.readLine())));
			byte[] oversized = new byte[DesktopBridge.MAX_FRAME_BYTES + 1];
			Arrays.fill(oversized, (byte) 'x');
			socket.getOutputStream().write(oversized);
			socket.getOutputStream().flush();

			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
			while (System.nanoTime() < deadline) {
				try {
					String line = reader.readLine();
					assertNotNull(line, "connection closed without a size error");
					Map<String, Object> frame = Protocol.decode(line);
					if (Protocol.ERROR.equals(Protocol.type(frame))) {
						assertEquals("frame too large", Protocol.str(frame, "message", ""));
						return;
					}
				} catch (SocketTimeoutException waiting) {
					// Ignore ordinary state/snapshot pushes while waiting for the error.
				}
			}
			fail("bridge did not reject the oversized frame");
		}
	}

	@Test void handshakeReplyPrecedesConcurrentStatePushes() throws Exception {
		var enteredVersionLookup = new java.util.concurrent.CountDownLatch(1);
		var finishVersionLookup = new java.util.concurrent.CountDownLatch(1);
		Supplier<String> delayedVersion = () -> {
			enteredVersionLookup.countDown();
			try {
				if (!finishVersionLookup.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to finish the handshake");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(interrupted);
			}
			return "test";
		};
		try (DesktopBridge bridge = startBridge(delayedVersion); Socket socket = connect(bridge)) {
			BufferedReader input = reader(socket);
			send(socket, Protocol.encodeHello(bridge.token(), "test") + "\n");
			try {
				assertTrue(enteredVersionLookup.await(2, java.util.concurrent.TimeUnit.SECONDS));
				// State pushes start at 200 ms; hold the handshake open past that.
				Thread.sleep(500);
			} finally {
				finishVersionLookup.countDown();
			}
			assertEquals(Protocol.HELLO_OK, Protocol.type(Protocol.decode(input.readLine())));
		}
	}

	@Test void replacingAClientDoesNotReplayLinesFromItsPredecessor() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket first = connect(bridge); Socket second = connect(bridge)) {
			BufferedReader firstInput = reader(first);
			send(first, Protocol.encodeHello(bridge.token(), "first") + "\n");
			assertEquals(Protocol.HELLO_OK, Protocol.type(Protocol.decode(firstInput.readLine())));
			assertEquals(Protocol.CONSOLE_SNAPSHOT, Protocol.type(Protocol.decode(firstInput.readLine())));
			Object firstConnection = awaitConnection(bridge, null);
			var scheduler = (java.util.concurrent.ScheduledExecutorService) privateField(bridge, "scheduler");
			scheduler.shutdownNow();
			assertTrue(scheduler.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
			ChampionConsole console = (ChampionConsole) privateField(bridge, "console");
			Object firstWriter = privateField(firstConnection, "writer");
			var pushConsole = DesktopBridge.class.getDeclaredMethod("pushConsole");
			pushConsole.setAccessible(true);
			var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
			Thread oldPush = new Thread(() -> {
				try { pushConsole.invoke(bridge); }
				catch (Throwable error) { failure.set(error); }
			});
			BufferedReader secondInput = reader(second);
			synchronized (firstWriter) {
				console.emit(ConsoleLine.info("before-old-push"));
				oldPush.start();
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
				while (oldPush.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
				assertEquals(Thread.State.BLOCKED, oldPush.getState(), "old push should be waiting for its writer");
				console.emit(ConsoleLine.info("already-in-new-snapshot"));
				send(second, Protocol.encodeHello(bridge.token(), "second") + "\n");
				assertEquals(Protocol.HELLO_OK, Protocol.type(Protocol.decode(secondInput.readLine())));
				var snapshot = Protocol.decode(secondInput.readLine());
				assertEquals(Protocol.CONSOLE_SNAPSHOT, Protocol.type(snapshot));
				assertTrue(Protocol.decodeLines(snapshot, "lines").stream().anyMatch(line -> line.text().equals("already-in-new-snapshot")));
				awaitConnection(bridge, firstConnection);
			}
			oldPush.join(3_000);
			assertFalse(oldPush.isAlive(), "old push did not finish");
			assertNull(failure.get());
			console.emit(ConsoleLine.info("new-after-attach"));
			pushConsole.invoke(bridge);
			var delta = Protocol.decode(secondInput.readLine());
			assertEquals(Protocol.CONSOLE_LINES, Protocol.type(delta));
			List<String> texts = Protocol.decodeLines(delta, "lines").stream().map(ConsoleLine::text).toList();
			assertEquals(List.of("new-after-attach"), texts);
		}
	}

	@Test void limitsInboundFrameBurstsIncludingBlankFrames() throws Exception {
		try (DesktopBridge bridge = startBridge(); Socket socket = connect(bridge)) {
			BufferedReader input = reader(socket);
			send(socket, Protocol.encodeHello(bridge.token(), "test") + "\n");
			assertEquals(Protocol.HELLO_OK, Protocol.type(Protocol.decode(input.readLine())));
			send(socket, "\n".repeat(101));
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
			while (System.nanoTime() < deadline) {
				try {
					String line = input.readLine();
					assertNotNull(line, "connection closed without a rate-limit error");
					Map<String, Object> frame = Protocol.decode(line);
					if (Protocol.ERROR.equals(Protocol.type(frame))) {
						assertEquals("frame rate limit exceeded", Protocol.str(frame, "message", ""));
						return;
					}
				} catch (SocketTimeoutException waiting) {
					// Ignore ordinary state/snapshot pushes while waiting for the error.
				}
			}
			fail("bridge did not reject the frame burst");
		}
	}

	private DesktopBridge startBridge() throws Exception {
		return startBridge(() -> "test");
	}

	private DesktopBridge startBridge(Supplier<String> version) throws Exception {
		DesktopBridge bridge = createBridge(version);
		bridge.start();
		return bridge;
	}

	private DesktopBridge createBridge(Supplier<String> version) throws Exception {
		Supplier<ModuleContext> context = () -> new ModuleContext(false, false, false, false,
			"unknown", "unknown", "unknown", 0, 0, 0, "unknown", "Tester", "0,0,0",
			"unknown", 0, 0, 0, List.of());
		ChampionConsole console = new ChampionConsole(temporary, context);
		return new DesktopBridge(console, context, List::of, version, Runnable::run, temporary);
	}

	private static Object privateField(Object target, String name) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Object awaitConnection(DesktopBridge bridge, Object previous) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			Object current = privateField(bridge, "attached");
			if (current != null && current != previous) return current;
			Thread.sleep(5);
		}
		throw new AssertionError("desktop connection was not published");
	}

	private static Socket connect(DesktopBridge bridge) throws Exception {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", bridge.port()), 2_000);
		socket.setSoTimeout(3_000);
		return socket;
	}

	private static BufferedReader reader(Socket socket) throws Exception {
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
	}

	private static void send(Socket socket, String text) throws Exception {
		socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}
}
