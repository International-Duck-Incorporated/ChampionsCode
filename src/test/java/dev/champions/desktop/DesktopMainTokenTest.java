package dev.champions.desktop;

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
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopMainTokenTest {
	@TempDir Path temporary;

	@Test void attachesUsingTheTokenPassedInTheEnvironment() throws Exception {
		assertHelloToken("secret-from-environment", null, "secret-from-environment");
	}

	@Test void stillAcceptsAnExplicitTokenForManualLaunches() throws Exception {
		assertHelloToken(null, "manual-token", "manual-token");
	}

	@Test void explicitTokenOverridesTheEnvironment() throws Exception {
		assertHelloToken("environment-token", "manual-token", "manual-token");
	}

	private void assertHelloToken(String environmentToken, String argumentToken, String expected) throws Exception {
		try (ServerSocket server = new ServerSocket()) {
			server.bind(new InetSocketAddress("127.0.0.1", 0));
			server.setSoTimeout(10_000);
			String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
			Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
			Path mainClasses = Path.of(DesktopMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			var command = new ArrayList<>(java.util.List.of(javaExecutable.toString(), "-Djava.awt.headless=true", "-cp", mainClasses.toString(),
				DesktopMain.class.getName(), "--connect", Integer.toString(server.getLocalPort()), "--config", temporary.toString()));
			if (argumentToken != null) {
				command.add("--token");
				command.add(argumentToken);
			}
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			builder.redirectOutput(Files.createTempFile(temporary, "desktop-test-", ".log").toFile());
			if (environmentToken == null) builder.environment().remove("CHAMPIONS_DESKTOP_TOKEN");
			else builder.environment().put("CHAMPIONS_DESKTOP_TOKEN", environmentToken);
			Process process = builder.start();
			try (Socket socket = server.accept()) {
				socket.setSoTimeout(5_000);
				BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				Map<String, Object> hello = Protocol.decode(input.readLine());
				assertEquals(Protocol.HELLO, Protocol.type(hello));
				assertEquals(expected, Protocol.str(hello, "token", ""));
			} finally {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}
}
