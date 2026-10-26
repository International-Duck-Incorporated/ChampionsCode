package dev.champions.core.module.payload;

/**
 * What a payload module is allowed to do to prove an impact.
 *
 * <p>An audit finding without a reproduction is a rumour, so a payload closes the
 * loop: it performs the smallest observable action that demonstrates the issue
 * actually exists, and records it as evidence. The vocabulary is deliberately
 * tiny — each kind is something a person could undo by hand in a few seconds.</p>
 *
 * <p>What is <em>not</em> in this list is the point: nothing that destroys data,
 * duplicates items, floods a server, breaks a chunk, or gains an advantage. If a
 * proof needs one of those, it is a finding to report, not a payload to run.</p>
 */
public record PayloadEffect(Kind kind, String value, boolean createsState) {

	public enum Kind {
		/** One chat command the server will echo, for example {@code say}. */
		CHAT_COMMAND,
		/** One small marker file inside the audit folder, never outside it. */
		LOCAL_FILE,
		/** One text artifact inside the audit folder: a reproduction, never a transmission. */
		AUDIT_ARTIFACT,
		/** No side effect at all: the proof is in what came back. */
		NONE
	}

	public PayloadEffect {
		kind = kind == null ? Kind.NONE : kind;
		value = value == null ? "" : value.strip();
		if (kind == Kind.NONE) createsState = false;
	}

	public static PayloadEffect none() {
		return new PayloadEffect(Kind.NONE, "", false);
	}

	public static PayloadEffect chatCommand(String command) {
		return new PayloadEffect(Kind.CHAT_COMMAND, command, false);
	}

	public static PayloadEffect markerFile(String fileName) {
		return new PayloadEffect(Kind.LOCAL_FILE, fileName, true);
	}

	/**
	 * One written reproduction inside the audit folder. Unlike a chat command this
	 * leaves something behind, so it is state-creating and the revert plan has to
	 * say how it is removed.
	 */
	public static PayloadEffect auditArtifact(String fileName) {
		return new PayloadEffect(Kind.AUDIT_ARTIFACT, fileName, true);
	}

	/** One line for the console, the audit log and the module editor. */
	public String describe() {
		return switch (kind) {
			case NONE -> "no side effect; the proof is the response";
			case CHAT_COMMAND -> "sends one chat command: " + value;
			case LOCAL_FILE -> "writes one marker file: " + value;
			case AUDIT_ARTIFACT -> "writes one audit artifact into the audit folder: " + value;
		};
	}
}
