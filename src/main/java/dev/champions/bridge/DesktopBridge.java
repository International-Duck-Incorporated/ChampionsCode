package dev.champions.bridge;

import dev.champions.core.log.Log;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.net.ipc.Json;
import dev.champions.net.ipc.Protocol;


import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Loopback bridge that lets the external ChampionsCode desktop app drive the
 * live console of the running game.
 *
 * <p>The bridge owns no game state. It receives the same objects the in-game
 * UI receives ({@link ChampionConsole} plus a context snapshot supplier) and
 * forwards the same calls the in-game screens make, so the authorization
 * allowlist, the one-run {@code CONFIRM} phrase, the per-run exploit warning
 * and the append-only audit log all still execute inside the game process.</p>
 *
 * <p>Security properties: loopback bind only, peer address re-checked, a fresh
 * 256-bit token per launch passed to automatically launched children through
 * their environment rather than argv, constant-time token comparison, nothing
 * but {@code hello} honoured before a successful handshake, a single attached
 * client, and inbound frame and rate limits.</p>
 *
 * <p>This class is deliberately free of Minecraft and Fabric imports so it can
 * be compiled, unit-tested, and exercised headlessly.</p>
 */
public final class DesktopBridge implements AutoCloseable {
	public static final int MAX_FRAME_BYTES = 8 * 1024;
	private static final int MAX_FRAMES_PER_SECOND = 100;
	private static final long STATE_PUSH_MILLIS = 1_000L;
	private static final long CONSOLE_POLL_MILLIS = 50L;
	private static final long EXPLOIT_PROMPT_TIMEOUT_MILLIS = 60_000L;

	private final ChampionConsole console;
	private final Supplier<ModuleContext> contextSupplier;
	private final Supplier<List<String>> playerNames;
	private final Supplier<String> versionSupplier;
	private final Executor mainThread;
	private final Path sessionFile;

	private final ServerSocket server;
	private final Thread acceptor;
	private final ScheduledExecutorService scheduler;
	private final String token;
	private final AtomicLong requestIds = new AtomicLong();
	private final Map<String, PendingPrompt> prompts = new ConcurrentHashMap<>();

	private volatile Connection attached;
	private volatile ModuleContext cachedContext;
	private volatile boolean closed;
	private volatile boolean sessionPublished;

	/** One attached desktop session. */
	private static final class Connection {
		final Socket socket;
		final BufferedWriter writer;
		volatile long nextConsoleSequence;

		Connection(Socket socket, BufferedWriter writer) {
			this.socket = socket;
			this.writer = writer;
		}
	}

	private record PendingPrompt(ExploitWarningPrompt.Request request, long deadline) {}

	public DesktopBridge(ChampionConsole console, Supplier<ModuleContext> contextSupplier,
			Supplier<List<String>> playerNames, Supplier<String> versionSupplier,
			Executor mainThread, Path sessionDirectory) throws IOException {
		this.console = console;
		this.contextSupplier = contextSupplier;
		this.playerNames = playerNames == null ? List::of : playerNames;
		this.versionSupplier = versionSupplier == null ? () -> "unknown" : versionSupplier;
		this.mainThread = mainThread;
		this.token = newToken();
		Files.createDirectories(sessionDirectory);
		this.sessionFile = sessionDirectory.resolve(Protocol.SESSION_FILE);
		this.server = new ServerSocket();
		this.server.setReuseAddress(false);
		// The desktop client connects to 127.0.0.1 explicitly. getLoopbackAddress()
		// may instead return ::1 when IPv6 is preferred, making attachment fail.
		this.server.bind(new InetSocketAddress("127.0.0.1", 0));
		this.acceptor = new Thread(this::acceptLoop, "champions-desktop-acceptor");
		this.acceptor.setDaemon(true);
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "champions-desktop-bridge");
			thread.setDaemon(true);
			return thread;
		});
	}

	public int port() {
		return server.getLocalPort();
	}

	public String token() {
		return token;
	}

	public Path sessionFile() {
		return sessionFile;
	}

	public boolean isAttached() {
		Connection connection = attached;
		return connection != null && !connection.socket.isClosed();
	}

	public void start() throws IOException {
		try {
			writeSessionFile();
			acceptor.start();
			scheduler.scheduleWithFixedDelay(this::pushStateSafely, 200, STATE_PUSH_MILLIS, TimeUnit.MILLISECONDS);
			scheduler.scheduleWithFixedDelay(this::pushConsoleSafely, 200, CONSOLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
			scheduler.scheduleWithFixedDelay(this::expirePrompts, EXPLOIT_PROMPT_TIMEOUT_MILLIS, EXPLOIT_PROMPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			Log.info("desktop bridge listening on 127.0.0.1:{} (token in {})", port(), sessionFile);
		} catch (IOException | RuntimeException failure) {
			close();
			throw failure;
		}
	}

	/** Asks the attached app to raise one of its windows. */
	public void showWindow(String windowId) {
		send(Json.obj("t", Protocol.WINDOW_SHOW, "window", windowId));
	}

	/**
	 * Offers an exploit-research legal warning to the desktop app.
	 *
	 * @return true when the request was forwarded over the bridge, false when no
	 *         app is attached and the caller must show its own (in-game) prompt.
	 */
	public boolean offerExploitPrompt(ExploitWarningPrompt.Request request) {
		if (!isAttached()) return false;
		String requestId = "p" + requestIds.incrementAndGet();
		prompts.put(requestId, new PendingPrompt(request, System.currentTimeMillis() + EXPLOIT_PROMPT_TIMEOUT_MILLIS));
		boolean sent = send(Json.obj("t", Protocol.EXPLOIT_PROMPT, "requestId", requestId,
			"module", request.moduleId(), "name", request.moduleName(), "target", request.target(),
			"risk", request.riskSummary(), "timeoutMs", EXPLOIT_PROMPT_TIMEOUT_MILLIS));
		if (!sent) prompts.remove(requestId);
		return sent;
	}

	// ---- networking ------------------------------------------------------

	private void acceptLoop() {
		while (!closed) {
			try {
				Socket socket = server.accept();
				if (socket.getInetAddress() == null || !socket.getInetAddress().isLoopbackAddress()) {
					Log.warn("rejected non-loopback desktop connection from {}", socket.getInetAddress());
					socket.close();
					continue;
				}
				socket.setSoTimeout(10_000);
				Thread handler = new Thread(() -> handle(socket), "champions-desktop-client");
				handler.setDaemon(true);
				handler.start();
			} catch (IOException error) {
				if (!closed) Log.warn("desktop bridge accept failed: {}", error.getMessage());
				return;
			}
		}
	}

	private void handle(Socket socket) {
		try (socket;
			 BufferedInputStream reader = new BufferedInputStream(socket.getInputStream());
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			String first;
			try {
				first = readFrame(reader);
			} catch (FrameTooLargeException oversized) {
				reply(writer, Protocol.encodeError("frame too large"));
				return;
			}
			if (first == null) return;
			Map<String, Object> hello = Protocol.decode(first);
			if (!Protocol.HELLO.equals(Protocol.type(hello))) {
				reply(writer, Protocol.encodeError("handshake required"));
				return;
			}
			if (!Protocol.supports(Protocol.num(hello, "proto", -1))) {
				reply(writer, Protocol.encodeError("unsupported protocol version"));
				return;
			}
			if (!tokenMatches(Protocol.str(hello, "token", ""))) {
				Log.warn("desktop bridge rejected a client with an invalid token");
				reply(writer, Protocol.encodeError("invalid token"));
				return;
			}

			Connection connection = new Connection(socket, writer);
			// Do not expose this connection to the scheduler until hello.ok and the
			// initial snapshot have been sent, or a state push can beat the handshake.
			ModuleContext context = cachedContext;
			reply(writer, Protocol.encodeHelloOk(versionSupplier.get(),
				context == null ? "unknown" : context.serverAddress(), console.currentTargetView().authorized()));
			List<ConsoleLine> initialLines;
			String initialPrompt;
			long initialSequence;
			synchronized (console) {
				initialLines = console.lines();
				initialPrompt = console.prompt();
				initialSequence = console.lineSequence();
			}
			if (!sendTo(connection, Protocol.encodeConsoleSnapshot(initialLines, initialPrompt))) return;
			socket.setSoTimeout(0);
			Connection previous;
			synchronized (this) {
				if (closed) return;
				previous = attached;
				connection.nextConsoleSequence = initialSequence;
				attached = connection;
			}
			if (previous != null) closeQuietly(previous.socket);
			Log.info("desktop app attached ({}), protocol {}", Protocol.str(hello, "app", "?"), Protocol.VERSION);

			FrameRateLimiter rate = new FrameRateLimiter();
			while (!closed) {
				String frame;
				try {
					frame = readFrame(reader);
				} catch (FrameTooLargeException oversized) {
					sendTo(connection, Protocol.encodeError("frame too large"));
					break;
				}
				if (frame == null) break;
				// Count blank frames as well, otherwise they bypass the rate bound.
				if (!rate.allow(System.nanoTime())) {
					sendTo(connection, Protocol.encodeError("frame rate limit exceeded"));
					break;
				}
				if (!frame.isBlank()) dispatch(frame);
			}
		} catch (IOException error) {
			Log.debug("desktop session ended: {}", error.getMessage());
		} catch (RuntimeException error) {
			Log.warn("desktop session failed: {}", error.toString());
		} finally {
			synchronized (this) {
				if (attached != null && attached.socket == socket) attached = null;
			}
		}
	}

	private void dispatch(String frame) {
		Map<String, Object> message;
		try {
			message = Protocol.decode(frame);
		} catch (RuntimeException malformed) {
			send(Protocol.encodeError("malformed frame: " + malformed.getMessage()));
			return;
		}
		String type = Protocol.type(message);
		try {
			switch (type) {
				case Protocol.CONSOLE_SUBMIT -> {
					String line = Protocol.str(message, "line", "");
					if (line.length() > 2_048) {
						send(Protocol.encodeError("command too long"));
						break;
					}
					onMainThread(() -> console.submit(line));
				}
				case Protocol.MODULES_SELECT -> {
					String id = Protocol.str(message, "id", "");
					onMainThread(() -> console.selectModuleFromUi(id));
				}
				case Protocol.MODULES_SET -> {
					String name = Protocol.str(message, "name", "");
					String value = Protocol.str(message, "value", "");
					if (value.length() > 4_096) {
						send(Protocol.encodeError("option value too long"));
						break;
					}
					onMainThread(() -> console.setOptionFromUi(name, value));
				}
				case Protocol.MODULES_RUN -> onMainThread(console::runFromUi);
				case Protocol.MODULES_CLEAR -> onMainThread(console::clearSelectionFromUi);
				case Protocol.AUTHORIZE_SET -> {
					boolean authorized = Protocol.bool(message, "authorized", false);
					onMainThread(() -> console.setCurrentTargetAuthorizedFromUi(authorized));
				}
				case Protocol.EXPLOIT_DECISION -> decideExploitPrompt(
					Protocol.str(message, "requestId", ""), Protocol.bool(message, "approved", false));
				case Protocol.JAVA_LIST -> onMainThread(() -> console.submit("java modules sources"));
				case Protocol.JAVA_COMPILE -> onMainThread(() -> console.submit("java modules compile"));
				case Protocol.JAVA_RELOAD -> onMainThread(() -> console.submit("java modules reload"));
				case Protocol.JAVA_UNLOAD -> onMainThread(() -> console.submit("java modules unload"));
				case Protocol.BYE -> {
					send(Protocol.encodeBye("client detached"));
					Log.info("desktop app detached");
				}
				default -> send(Protocol.encodeError("unsupported frame type: " + type));
			}
		} catch (RuntimeException error) {
			send(Protocol.encodeError("frame handling failed: " + error.getMessage()));
		}
	}

	private void decideExploitPrompt(String requestId, boolean approved) {
		PendingPrompt pending = prompts.remove(requestId);
		if (pending == null) {
			send(Protocol.encodeError("unknown or expired exploit prompt: " + requestId));
			return;
		}
		onMainThread(approved ? pending.request().confirm() : pending.request().cancel());
	}

	private void expirePrompts() {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, PendingPrompt> entry : new ArrayList<>(prompts.entrySet())) {
			if (entry.getValue().deadline() > now) continue;
			if (prompts.remove(entry.getKey()) == null) continue;
			Log.warn("exploit prompt {} timed out; denying by default", entry.getKey());
			onMainThread(entry.getValue().request().cancel());
		}
	}

	private void onMainThread(Runnable action) {
		try {
			mainThread.execute(() -> {
				try {
					action.run();
				} catch (RuntimeException error) {
					send(Protocol.encodeError("action failed: " + error.getMessage()));
				}
			});
		} catch (RuntimeException rejected) {
			send(Protocol.encodeError("main thread rejected the action"));
		}
	}

	// ---- pushes ----------------------------------------------------------

	private void pushStateSafely() {
		try {
			pushState();
		} catch (RuntimeException error) {
			Log.debug("state push failed: {}", error.getMessage());
		}
	}

	private void pushState() {
		onMainThreadQuietly(() -> cachedContext = contextSupplier.get());
		if (!isAttached()) return;
		ModuleContext context = cachedContext;
		ChampionConsole.CurrentTargetView target = console.currentTargetView();

		List<Object> modules = new ArrayList<>();
		for (ChampionConsole.ModuleView view : console.moduleViews()) {
			modules.add(Json.map("id", view.id(), "name", view.name(), "description", view.description(),
				"selected", view.selected(), "exploit", view.exploitResearch()));
		}
		Map<String, Object> active = null;
		ChampionConsole.ActiveModuleView activeView = console.activeModuleView();
		if (activeView != null) {
			List<Object> options = new ArrayList<>();
			for (ChampionConsole.OptionView option : activeView.options()) {
				options.add(Json.map("name", option.name(), "value", option.secret() && !option.configured() ? "" : option.value(),
					"default", option.defaultValue(), "required", option.required(), "secret", option.secret(),
					"configured", option.configured(), "description", option.description(),
					"choices", option.choices()));
			}
			active = mapOf("id", activeView.id(), "name", activeView.name(),
				"description", activeView.description(), "exploit", activeView.exploitResearch(),
				"options", options);
		}

		Map<String, Object> state = new LinkedHashMap<>();
		state.put("connected", context != null && context.connected());
		state.put("target", context == null ? "unknown" : context.serverAddress());
		state.put("brand", context == null ? "unknown" : context.serverBrand());
		state.put("player", context == null ? "unknown" : context.playerName());
		state.put("authorized", target.available() && target.authorized());
		state.put("authorizeAvailable", target.available());
		state.put("running", console.runningModuleId());
		state.put("prompt", console.prompt());
		state.put("active", active);
		state.put("modules", modules);
		state.put("players", playerNames.get());
		var capabilities = console.capabilities();
		state.put("capabilities", capabilities == null ? "" : capabilities.summary());
		send(Protocol.encodeState(state));
	}

	private void pushConsoleSafely() {
		try {
			pushConsole();
		} catch (RuntimeException error) {
			Log.debug("console push failed: {}", error.getMessage());
		}
	}

	private void pushConsole() {
		Connection connection = attached;
		if (connection == null || connection.socket.isClosed()) return;
		long total;
		List<ConsoleLine> lines;
		String prompt;
		synchronized (console) {
			total = console.lineSequence();
			lines = console.lines();
			prompt = console.prompt();
		}
		long firstSequence = total - lines.size();
		long cursor = connection.nextConsoleSequence;
		if (cursor > total) cursor = firstSequence;
		if (cursor < firstSequence) {
			if (sendTo(connection, Protocol.encodeConsoleSnapshot(lines, prompt))) connection.nextConsoleSequence = total;
			return;
		}
		int fromIndex = (int) (cursor - firstSequence);
		if (fromIndex >= lines.size()) return;
		List<ConsoleLine> delta = lines.subList(fromIndex, lines.size());
		if (sendTo(connection, Protocol.encodeConsoleLines(fromIndex, delta))) connection.nextConsoleSequence = total;
	}

	// ---- plumbing --------------------------------------------------------

	/** Reads an NDJSON frame with a byte limit, before allocating an unbounded line. */
	private static String readFrame(InputStream input) throws IOException {
		byte[] bytes = new byte[MAX_FRAME_BYTES + 1];
		int length = 0;
		while (true) {
			int next = input.read();
			if (next < 0) {
				if (length == 0) return null;
				break;
			}
			if (next == '\n') break;
			// Leave room for the CR in a CRLF terminator after a maximum-size frame.
			if (length >= bytes.length || (length == MAX_FRAME_BYTES && next != '\r')) {
				throw new FrameTooLargeException();
			}
			bytes[length++] = (byte) next;
		}
		if (length > 0 && bytes[length - 1] == '\r') length--;
		return new String(bytes, 0, length, StandardCharsets.UTF_8);
	}

	private static final class FrameTooLargeException extends IOException {}

	/** Sliding one-second window, confined to one connection's reader thread. */
	private static final class FrameRateLimiter {
		private final long[] recent = new long[MAX_FRAMES_PER_SECOND];
		private int count;
		private int next;

		boolean allow(long now) {
			if (count == recent.length && now - recent[next] < TimeUnit.SECONDS.toNanos(1)) return false;
			recent[next] = now;
			next = (next + 1) % recent.length;
			if (count < recent.length) count++;
			return true;
		}
	}

	private void onMainThreadQuietly(Runnable action) {
		try {
			mainThread.execute(() -> {
				try {
					action.run();
				} catch (RuntimeException ignored) {
					// A failed context sample keeps the previous snapshot.
				}
			});
		} catch (RuntimeException ignored) {
			// Executor shutting down; keep the cached snapshot.
		}
	}

	private boolean send(String frame) {
		Connection connection = attached;
		if (connection == null) return false;
		return sendTo(connection, frame);
	}

	private boolean sendTo(Connection connection, String frame) {
		synchronized (connection.writer) {
			try {
				connection.writer.write(frame);
				connection.writer.write('\n');
				connection.writer.flush();
				return true;
			} catch (IOException gone) {
				closeQuietly(connection.socket);
				synchronized (this) {
					if (attached == connection) attached = null;
				}
				return false;
			}
		}
	}

	private static void reply(BufferedWriter writer, String frame) {
		try {
			writer.write(frame);
			writer.write('\n');
			writer.flush();
		} catch (IOException ignored) {
			// The peer is gone; the read loop will end.
		}
	}

	private boolean tokenMatches(String candidate) {
		if (candidate == null) return false;
		byte[] expected = token.getBytes(StandardCharsets.UTF_8);
		byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private void writeSessionFile() throws IOException {
		String json = Json.obj("protocol", Protocol.VERSION, "port", port(), "pid",
			ProcessHandle.current().pid(), "token", token);
		Path temporary;
		try {
			temporary = Files.createTempFile(sessionFile.getParent(), ".desktop-session-", ".tmp",
				PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
		} catch (UnsupportedOperationException windows) {
			// On filesystems without POSIX modes, use the directory's inherited ACLs.
			temporary = Files.createTempFile(sessionFile.getParent(), ".desktop-session-", ".tmp");
		}
		try {
			Files.writeString(temporary, json, StandardCharsets.UTF_8,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
			try {
				Files.move(temporary, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unavailable) {
				Files.move(temporary, sessionFile, StandardCopyOption.REPLACE_EXISTING);
			}
			sessionPublished = true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
			// already closed
		}
	}

	private static Map<String, Object> mapOf(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
		return map;
	}

	@Override
	public void close() {
		closed = true;
		scheduler.shutdownNow();
		Connection connection = attached;
		if (connection != null) closeQuietly(connection.socket);
		try {
			server.close();
		} catch (IOException ignored) {
			// already closed
		}
		if (sessionPublished) {
			try {
				Files.deleteIfExists(sessionFile);
			} catch (IOException ignored) {
				// the file is stale either way; the token changes on the next launch
			}
			sessionPublished = false;
		}
		for (PendingPrompt prompt : prompts.values()) onMainThread(prompt.request()::cancel);
		prompts.clear();
		Log.info("desktop bridge stopped");
	}

	/** Kept for hosts that want to log what the desktop saw. */
	public static List<String> summarize(List<ModuleOption> options) {
		List<String> out = new ArrayList<>();
		for (ModuleOption option : options) out.add(option.name());
		return List.copyOf(out);
	}
}
