package dev.champions.core.module.payload;

import java.util.Locale;
import java.util.Set;

/**
 * The boundary around a payload's declared effect.
 *
 * <p>A payload module states what it will do before it runs; this decides whether
 * that statement is acceptable. It is a refusal list, not a permission list: an
 * effect that is not recognisably benign is rejected with the reason, so a module
 * cannot get an unknown, louder action past the gate by describing it as a
 * "probe".</p>
 *
 * <p>Everything here is a pure function of the declaration, which is what makes it
 * testable without a server, a client or a network — see {@code --self-test}.</p>
 */
public final class PayloadSafety {
	/**
	 * Chat commands a proof may use. Each is visible to everyone present and
	 * changes nothing in the world: the server echoes it, players read it. The
	 * list is short on purpose — commands that grant, give, teleport, kick, ban,
	 * or alter world state are absent, and absence means refused.
	 */
	private static final Set<String> ALLOWED_CHAT_ROOTS = Set.of("say", "me", "list", "help", "seed", "time", "gamemode");

	/** Roots that look similar but change state, so they are named explicitly. */
	private static final Set<String> REFUSED_CHAT_ROOTS = Set.of(
			"op", "deop", "give", "kill", "kick", "ban", "pardon", "tp", "teleport", "summon",
			"setblock", "fill", "clone", "effect", "difficulty", "gamerule", "stop", "reload",
			"whitelist", "clear", "enchant", "xp", "spawnpoint", "weather", "time_set", "transfer");

	/** Guards against a command hidden behind alias chains or NBT payloads. */
	private static final Set<String> FORBIDDEN_SUBSTRINGS = Set.of(
			"\n", "\r", "\u0000", "{\"", "${", "$(", "`");

	/**
	 * Extensions an artifact may use. All three are inert text that a person reads:
	 * none is executable, none is loaded by this client, and the client never sends
	 * one anywhere.
	 */
	private static final Set<String> ALLOWED_ARTIFACT_EXTENSIONS = Set.of("yml", "yaml", "sk", "txt", "md", "json");

	private static final int MAX_COMMAND_LENGTH = 240;
	private static final int MAX_ARTIFACT_BYTES = 8192;
	private static final int MAX_FILENAME_LENGTH = 64;
	private static final long MAX_FILE_BYTES = 1024;

	private PayloadSafety() {}

	/** The verdict for one declared effect. */
	public record Verdict(boolean allowed, String reason) {
		public static Verdict allow(String reason) {
			return new Verdict(true, reason);
		}

		public static Verdict refuse(String reason) {
			return new Verdict(false, reason);
		}
	}

	public static Verdict check(PayloadEffect effect) {
		if (effect == null) return Verdict.refuse("No declared payload effect.");
		return switch (effect.kind()) {
			case NONE -> Verdict.allow("No side effect declared.");
			case CHAT_COMMAND -> checkCommand(effect.value());
			case LOCAL_FILE -> checkFile(effect.value());
			case AUDIT_ARTIFACT -> checkArtifactFile(effect.value());
		};
	}

	/** Public form for the runtime, which validates the exact wire command before sending. */
	public static Verdict checkChatCommand(String command) {
		return checkCommand(command);
	}

	private static Verdict checkCommand(String command) {
		String body = command == null ? "" : command.strip();
		if (body.startsWith("/")) body = body.substring(1).strip();
		if (body.isEmpty()) return Verdict.refuse("Declared chat command is empty.");
		if (body.length() > MAX_COMMAND_LENGTH) {
			return Verdict.refuse("Chat command is longer than " + MAX_COMMAND_LENGTH + " characters.");
		}
		for (String forbidden : FORBIDDEN_SUBSTRINGS) {
			if (body.contains(forbidden)) {
				return Verdict.refuse("Chat command contains '" + printable(forbidden)
						+ "', which can smuggle a second command or a data payload.");
			}
		}
		String root = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);

		// "gamemode" and "time" only read when they ask; a change is refused below.
		boolean readOnlyForm = (root.equals("time") && body.toLowerCase(Locale.ROOT).contains("query"))
				|| (root.equals("gamemode") && body.toLowerCase(Locale.ROOT).contains("query"));
		if (REFUSED_CHAT_ROOTS.contains(root) && !readOnlyForm) {
			return Verdict.refuse("'/"+ root + "' changes server or player state; payloads prove reach, they do not alter state.");
		}
		if (!ALLOWED_CHAT_ROOTS.contains(root)) {
			return Verdict.refuse("'/"+ root + "' is not on the payload allowlist (say, me, list, help, seed, time query).");
		}
		return Verdict.allow("One visible, state-neutral chat command.");
	}

	private static Verdict checkFile(String fileName) {
		String name = fileName == null ? "" : fileName.strip();
		if (name.isEmpty()) return Verdict.refuse("Declared marker file has no name.");
		if (name.length() > MAX_FILENAME_LENGTH) {
			return Verdict.refuse("Marker file name is longer than " + MAX_FILENAME_LENGTH + " characters.");
		}
		if (name.contains("/") || name.contains("\\")) {
			return Verdict.refuse("Marker file must be a bare name: a path could escape the audit folder.");
		}
		if (name.contains("..")) return Verdict.refuse("Marker file name contains '..'.");
		if (!name.matches("[A-Za-z0-9._-]+")) {
			return Verdict.refuse("Marker file name may only use letters, digits, dot, dash and underscore.");
		}
		return Verdict.allow("One marker file inside the audit folder, at most " + MAX_FILE_BYTES + " bytes.");
	}

	/**
	 * An artifact is a named text file with an inert extension. It is held to the
	 * same containment rule as a marker — a bare name, inside the audit folder —
	 * because an artifact that could pick its own path could overwrite anything the
	 * client can write.
	 */
	private static Verdict checkArtifactFile(String fileName) {
		Verdict containment = checkFile(fileName);
		if (!containment.allowed()) return containment;
		int dot = fileName.lastIndexOf('.');
		if (dot < 0) return Verdict.refuse("Audit artifact needs an extension so it is obvious what it is.");
		String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
		if (!ALLOWED_ARTIFACT_EXTENSIONS.contains(extension)) {
			return Verdict.refuse("'" + extension + "' is not an inert text extension ("
				+ String.join(", ", ALLOWED_ARTIFACT_EXTENSIONS.stream().sorted().toList()) + ").");
		}
		if (dot == 0) return Verdict.refuse("Audit artifact needs a name before its extension.");
		return Verdict.allow("One text artifact inside the audit folder, at most " + MAX_ARTIFACT_BYTES + " bytes.");
	}

	/** True when the artifact a module wrote is inside the artifact size ceiling. */
	public static boolean acceptableArtifactSize(long bytes) {
		return bytes >= 0 && bytes <= MAX_ARTIFACT_BYTES;
	}

	public static long maximumArtifactBytes() {
		return MAX_ARTIFACT_BYTES;
	}

	/** True when the marker file a module wrote is small enough to be a marker. */
	public static boolean acceptableFileSize(long bytes) {
		return bytes >= 0 && bytes <= MAX_FILE_BYTES;
	}

	public static long maximumFileBytes() {
		return MAX_FILE_BYTES;
	}

	private static String printable(String value) {
		StringBuilder out = new StringBuilder();
		for (char character : value.toCharArray()) {
			if (character < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
			else out.append(character);
		}
		return out.toString();
	}
}
