package dev.champions.net.ipc;

import dev.champions.core.console.ConsoleLine;
import dev.champions.core.update.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ChampionsCode desktop protocol: newline-delimited JSON frames over
 * loopback, exchanged between the game process (server) and the desktop app
 * (client).
 *
 * <p>Every frame is a flat JSON object with a {@code t} (type) field and an
 * optional {@code id} correlation field. The vocabulary is deliberately small
 * and contains no generic "execute" verb: the only mutating operations are the
 * same bounded calls the in-game UI makes against {@code ChampionConsole}.</p>
 */
public final class Protocol {
	public static final int VERSION = 1;
	public static final String SESSION_FILE = "desktop-session.json";

	// handshake
	public static final String HELLO = "hello";
	public static final String HELLO_OK = "hello.ok";
	public static final String ERROR = "error";
	public static final String BYE = "bye";

	// server -> client stream
	public static final String STATE_PUSH = "state.push";
	public static final String CONSOLE_LINES = "console.lines";

	// client -> server console facade
	public static final String CONSOLE_SUBMIT = "console.submit";
	public static final String CONSOLE_SNAPSHOT = "console.snapshot";

	// client -> server module facade
	public static final String MODULES_SELECT = "modules.select";
	public static final String MODULES_SET = "modules.set";
	public static final String MODULES_RUN = "modules.run";
	public static final String MODULES_CLEAR = "modules.clear";
	public static final String AUTHORIZE_SET = "authorize.set";

	// exploit-research warning round-trip
	public static final String EXPLOIT_PROMPT = "exploit.prompt";
	public static final String EXPLOIT_DECISION = "exploit.decision";

	// code space (bounded, identical to the in-game Java Studio operations)
	public static final String JAVA_LIST = "java.list";
	public static final String JAVA_READ = "java.read";
	public static final String JAVA_WRITE = "java.write";
	public static final String JAVA_COMPILE = "java.compile";
	public static final String JAVA_RELOAD = "java.reload";
	public static final String JAVA_UNLOAD = "java.unload";

	// window routing (game asks the app to raise a window)
	public static final String WINDOW_SHOW = "window.show";

	private Protocol() {}

	// ---- decoding --------------------------------------------------------

	/** Parses one frame; malformed input is rejected before it can reach any handler. */
	public static Map<String, Object> decode(String line) {
		if (line == null || line.isBlank()) throw new IllegalArgumentException("empty frame");
		Object value = MiniJson.parse(line);
		if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("frame is not a JSON object");
		@SuppressWarnings("unchecked")
		Map<String, Object> typed = (Map<String, Object>) map;
		return typed;
	}

	/** True when a peer speaks a protocol version this build understands. */
	public static boolean supports(long peerVersion) {
		return peerVersion == VERSION;
	}

	public static String type(Map<String, Object> frame) {
		return str(frame, "t", "");
	}

	public static String str(Map<String, Object> frame, String key, String fallback) {
		Object value = frame.get(key);
		return value instanceof String text ? text : fallback;
	}

	public static long num(Map<String, Object> frame, String key, long fallback) {
		Object value = frame.get(key);
		return value instanceof Number number ? number.longValue() : fallback;
	}

	public static boolean bool(Map<String, Object> frame, String key, boolean fallback) {
		Object value = frame.get(key);
		return value instanceof Boolean flag ? flag : fallback;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> frame, String key) {
		Object value = frame.get(key);
		return value instanceof List<?> items ? (List<Object>) items : List.of();
	}

	public static List<String> strings(Map<String, Object> frame, String key) {
		List<String> out = new ArrayList<>();
		for (Object item : list(frame, key)) if (item instanceof String text) out.add(text);
		return List.copyOf(out);
	}

	// ---- encoding --------------------------------------------------------

	public static String encodeHello(String token, String appVersion) {
		return Json.obj("t", HELLO, "proto", VERSION, "token", token, "app", appVersion);
	}

	public static String encodeHelloOk(String modVersion, String target, boolean authorized) {
		return Json.obj("t", HELLO_OK, "proto", VERSION, "mod", modVersion,
			"target", target, "authorized", authorized);
	}

	public static String encodeError(String message) {
		return Json.obj("t", ERROR, "message", message == null ? "error" : message);
	}

	public static String encodeBye(String reason) {
		return Json.obj("t", BYE, "reason", reason == null ? "" : reason);
	}

	public static String encodeConsoleLines(int from, List<ConsoleLine> lines) {
		return Json.obj("t", CONSOLE_LINES, "from", from, "lines", encodeLines(lines));
	}

	public static String encodeConsoleSnapshot(List<ConsoleLine> lines, String prompt) {
		return Json.obj("t", CONSOLE_SNAPSHOT, "prompt", prompt, "lines", encodeLines(lines));
	}

	private static List<Object> encodeLines(List<ConsoleLine> lines) {
		List<Object> encoded = new ArrayList<>(lines.size());
		for (ConsoleLine line : lines) {
			encoded.add(Json.map("tone", line.tone().name(), "text", line.text()));
		}
		return encoded;
	}

	/** Decodes a {@code line(s)} array produced by {@link #encodeConsoleLines}. */
	public static List<ConsoleLine> decodeLines(Map<String, Object> frame, String key) {
		List<ConsoleLine> out = new ArrayList<>();
		for (Object item : list(frame, key)) {
			if (!(item instanceof Map<?, ?> map)) continue;
			@SuppressWarnings("unchecked")
			Map<String, Object> entry = (Map<String, Object>) map;
			String tone = str(entry, "tone", "INFO");
			String text = str(entry, "text", "");
			out.add(new ConsoleLine(toneOf(tone), text));
		}
		return List.copyOf(out);
	}

	private static ConsoleLine.Tone toneOf(String name) {
		try {
			return ConsoleLine.Tone.valueOf(name);
		} catch (IllegalArgumentException unknown) {
			return ConsoleLine.Tone.INFO;
		}
	}

	public static String encodeState(Map<String, Object> state) {
		Map<String, Object> frame = new java.util.LinkedHashMap<>(state);
		frame.put("t", STATE_PUSH);
		return Json.of(frame);
	}
}
