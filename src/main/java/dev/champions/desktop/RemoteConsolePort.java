package dev.champions.desktop;

import dev.champions.core.console.ConsoleLine;
import dev.champions.net.ipc.Json;
import dev.champions.net.ipc.Protocol;
import dev.champions.surface.ConsoleBuffer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Port attached to a running game over the loopback bridge.
 *
 * <p>The app never inspects the game directly: it receives the same console
 * lines, module views and option values the in-game UI renders, and sends back
 * the same calls the in-game UI makes. Authorization, the one-run
 * {@code CONFIRM} phrase, the exploit warning and the audit trail all stay in
 * the game process.</p>
 */
public final class RemoteConsolePort implements ConsolePort {
	private final Socket socket;
	private final BufferedWriter writer;
	private final BufferedReader reader;
	private final ConsoleBuffer buffer = new ConsoleBuffer();
	private final AtomicBoolean closed = new AtomicBoolean();

	private volatile String prompt = "champions";
	private volatile Status status = Status.offline("connecting");
	private volatile List<ModuleInfo> modules = List.of();
	private volatile ActiveInfo active;
	private volatile String mode = "ATTACHED";
	private volatile String modVersion = "unknown";
	private volatile String capabilities = "";
	private volatile Runnable exploitPromptListener = () -> {};

	private RemoteConsolePort(Socket socket) throws IOException {
		this.socket = socket;
		this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
		this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
	}

	/** Connects, performs the handshake, and starts the reader thread. */
	public static RemoteConsolePort connect(String host, int port, String token, String appVersion) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5_000);
		socket.setSoTimeout(10_000);
		RemoteConsolePort client = new RemoteConsolePort(socket);
		client.send(Protocol.encodeHello(token, appVersion));
		String line = client.reader.readLine();
		if (line == null) throw new IOException("The game closed the connection during the handshake.");
		Map<String, Object> frame = Protocol.decode(line);
		if (Protocol.ERROR.equals(Protocol.type(frame))) {
			throw new IOException("Bridge refused the handshake: " + Protocol.str(frame, "message", "unknown reason"));
		}
		if (!Protocol.HELLO_OK.equals(Protocol.type(frame))) {
			throw new IOException("Unexpected handshake reply: " + Protocol.type(frame));
		}
		client.modVersion = Protocol.str(frame, "mod", "unknown");
		client.status = new Status(true, Protocol.str(frame, "target", "unknown"), "unknown", "unknown",
			Protocol.bool(frame, "authorized", false), true, "", "attached to the game");
		socket.setSoTimeout(0);
		Thread readerThread = new Thread(client::readLoop, "champions-desktop-reader");
		readerThread.setDaemon(true);
		readerThread.start();
		return client;
	}

	public String modVersion() {
		return modVersion;
	}

	/** Called on the Swing thread when the game asks to acknowledge an exploit warning. */
	public void onExploitPrompt(Runnable listener) {
		this.exploitPromptListener = listener == null ? () -> {} : listener;
	}

	public List<Map<String, Object>> pendingExploitPrompts() {
		return List.copyOf(exploitPrompts);
	}

	private final List<Map<String, Object>> exploitPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Answers a forwarded exploit warning. */
	public void answerExploitPrompt(String requestId, boolean approved) {
		exploitPrompts.removeIf(prompt -> requestId.equals(prompt.get("requestId")));
		send(Json.obj("t", Protocol.EXPLOIT_DECISION, "requestId", requestId, "approved", approved));
	}

	private void readLoop() {
		try {
			String line;
			while (!closed.get() && (line = reader.readLine()) != null) {
				if (line.isBlank()) continue;
				Map<String, Object> frame;
				try {
					frame = Protocol.decode(line);
				} catch (RuntimeException malformed) {
					continue;
				}
				handle(frame);
			}
		} catch (IOException error) {
			// fall through to the detached state
		} finally {
			if (!closed.get()) {
				mode = "DETACHED";
				status = Status.offline("the game session ended");
			}
		}
	}

	private void handle(Map<String, Object> frame) {
		switch (Protocol.type(frame)) {
			case Protocol.CONSOLE_LINES -> {
				List<ConsoleLine> lines = Protocol.decodeLines(frame, "lines");
				buffer.appendAll(lines);
			}
			case Protocol.CONSOLE_SNAPSHOT -> {
				buffer.reset(Protocol.decodeLines(frame, "lines"));
				prompt = Protocol.str(frame, "prompt", prompt);
			}
			case Protocol.STATE_PUSH -> {
				prompt = Protocol.str(frame, "prompt", prompt);
				status = new Status(
					Protocol.bool(frame, "connected", false),
					Protocol.str(frame, "target", "unknown"),
					Protocol.str(frame, "brand", "unknown"),
					Protocol.str(frame, "player", "unknown"),
					Protocol.bool(frame, "authorized", false),
					Protocol.bool(frame, "authorizeAvailable", false),
					Protocol.str(frame, "running", ""),
					"attached to the game");
				List<ModuleInfo> next = new ArrayList<>();
				for (Object item : Protocol.list(frame, "modules")) {
					if (!(item instanceof Map<?, ?> map)) continue;
					@SuppressWarnings("unchecked")
					Map<String, Object> module = (Map<String, Object>) map;
					next.add(new ModuleInfo(
						Protocol.str(module, "id", ""), Protocol.str(module, "name", ""),
						Protocol.str(module, "description", ""), Protocol.bool(module, "selected", false),
						Protocol.bool(module, "exploit", false)));
				}
				modules = List.copyOf(next);
				capabilities = Protocol.str(frame, "capabilities", capabilities);
				active = decodeActive(frame.get("active"));
			}
			case Protocol.EXPLOIT_PROMPT -> {
				Map<String, Object> promptRequest = new LinkedHashMap<>(frame);
				exploitPrompts.add(promptRequest);
				javax.swing.SwingUtilities.invokeLater(exploitPromptListener);
			}
			case Protocol.WINDOW_SHOW -> {
				String window = Protocol.str(frame, "window", "terminal");
				javax.swing.SwingUtilities.invokeLater(() -> DesktopMain.raiseWindow(window));
			}
			case Protocol.ERROR -> buffer.append(ConsoleLine.error("[bridge] " + Protocol.str(frame, "message", "error")));
			case Protocol.BYE -> {
				mode = "DETACHED";
				buffer.append(ConsoleLine.warning("[bridge] " + Protocol.str(frame, "reason", "detached")));
			}
			default -> {
				// Unknown frames are ignored so a newer game build never breaks the app.
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static ActiveInfo decodeActive(Object value) {
		if (!(value instanceof Map<?, ?> map)) return null;
		Map<String, Object> active = (Map<String, Object>) map;
		List<OptionInfo> options = new ArrayList<>();
		for (Object item : Protocol.list(active, "options")) {
			if (!(item instanceof Map<?, ?> optionMap)) continue;
			Map<String, Object> option = (Map<String, Object>) optionMap;
			options.add(new OptionInfo(
				Protocol.str(option, "name", ""), Protocol.str(option, "value", ""),
				Protocol.str(option, "default", ""), Protocol.bool(option, "required", false),
				Protocol.bool(option, "secret", false), Protocol.bool(option, "configured", false),
				Protocol.str(option, "description", ""), Protocol.strings(option, "choices")));
		}
		return new ActiveInfo(Protocol.str(active, "id", ""), Protocol.str(active, "name", ""),
			Protocol.str(active, "description", ""), Protocol.bool(active, "exploit", false), List.copyOf(options));
	}

	private synchronized void send(String frame) {
		try {
			writer.write(frame);
			writer.write('\n');
			writer.flush();
		} catch (IOException gone) {
			mode = "DETACHED";
			status = Status.offline("the game session ended");
		}
	}

	@Override public ConsoleBuffer buffer() { return buffer; }
	@Override public String prompt() { return prompt; }
	@Override public Status status() { return status; }
	@Override public List<ModuleInfo> modules() { return modules; }
	@Override public ActiveInfo active() { return active; }
	@Override public List<String> history() { return List.of(); }

	@Override public void submit(String line) { send(Json.obj("t", Protocol.CONSOLE_SUBMIT, "line", line)); }
	@Override public void selectModule(String id) { send(Json.obj("t", Protocol.MODULES_SELECT, "id", id)); }
	@Override public void setOption(String name, String value) { send(Json.obj("t", Protocol.MODULES_SET, "name", name, "value", value)); }
	@Override public void run() { send(Json.obj("t", Protocol.MODULES_RUN)); }
	@Override public void clearSelection() { send(Json.obj("t", Protocol.MODULES_CLEAR)); }
	@Override public void setAuthorized(boolean authorized) { send(Json.obj("t", Protocol.AUTHORIZE_SET, "authorized", authorized)); }
	@Override public String mode() { return mode; }
	@Override public String capabilities() { return capabilities; }

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		send(Protocol.encodeBye("desktop app closed"));
		try {
			socket.close();
		} catch (IOException ignored) {
			// already gone
		}
	}
}
