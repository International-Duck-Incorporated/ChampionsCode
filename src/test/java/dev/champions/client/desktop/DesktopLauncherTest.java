package dev.champions.client.desktop;

import dev.champions.net.ipc.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DesktopLauncherTest {
	@TempDir Path temporary;

	@Test void launchesFromSplitDevelopmentClassesWithoutPuttingTheTokenInArguments() throws Exception {
		Path clientClasses = Path.of(DesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue(Files.isDirectory(clientClasses), "this regression exercises Loom's split class directories");
		String token = "secret-token-for-launcher-test";
		DesktopLauncher launcher = new DesktopLauncher(temporary);
		Process child = null;
		try (ServerSocket server = new ServerSocket()) {
			server.bind(new InetSocketAddress("127.0.0.1", 0));
			server.setSoTimeout(10_000);
			try {
				assertTrue(launcher.spawn(server.getLocalPort(), token, temporary.toString()));
				var processField = DesktopLauncher.class.getDeclaredField("process");
				processField.setAccessible(true);
				child = (Process) processField.get(launcher);
				assertNotNull(child);
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(5_000);
					var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					var hello = Protocol.decode(input.readLine());
					assertEquals(Protocol.HELLO, Protocol.type(hello));
					assertEquals(token, Protocol.str(hello, "token", ""));
					// Process arguments are not available on every OS.
					child.info().arguments().ifPresent(arguments -> assertFalse(
						Arrays.stream(arguments).anyMatch(argument -> argument.contains(token)),
						"the token must not be visible in process arguments"));
				}
			} finally {
				launcher.close();
				if (child != null && !child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
			}
		}
	}
}
