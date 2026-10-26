package dev.champions.core.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The one folder a payload may write a reproduction into.
 *
 * <p>Everything about this class is containment. The folder is fixed when the
 * console is built, a file name is a bare name with no separators, the extension
 * is inert text, and the size ceiling comes from
 * {@link dev.champions.core.module.payload.PayloadSafety}. An artifact that could
 * choose its own path could overwrite the audit log, the authorization file or
 * anything else the client can write, so it cannot choose.</p>
 *
 * <p>An artifact is a document. Nothing in ChampionsCode reads it back into a
 * command, deploys it, or sends it anywhere — an operator opens it, decides, and
 * acts on a server they are authorized to test.</p>
 */
public final class AuditFolder {
	private final Path directory;

	private AuditFolder(Path directory) {
		this.directory = directory;
	}

	/** The artifact folder for a client config directory. */
	public static AuditFolder under(Path configDirectory) {
		return new AuditFolder(configDirectory.resolve("artifacts"));
	}

	/** A folder that refuses every write, for a front end with nowhere to put one. */
	public static AuditFolder unavailable() {
		return new AuditFolder(null);
	}

	public Optional<Path> directory() {
		return Optional.ofNullable(directory);
	}

	/** Outcome of one write. */
	public record Written(boolean ok, String message, Path path) {
		static Written failure(String message) {
			return new Written(false, message, null);
		}
	}

	/**
	 * Writes one artifact, replacing an earlier artifact of the same name so a
	 * repeated run does not accumulate copies.
	 */
	public Written write(String fileName, String content) {
		if (directory == null) return Written.failure("This front end has no artifact folder.");
		if (fileName == null || fileName.isBlank()) return Written.failure("No artifact file name.");
		if (content == null) return Written.failure("No artifact content.");
		if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return Written.failure("'" + fileName + "' is not a bare file name; artifacts stay inside the audit folder.");
		}
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (!dev.champions.core.module.payload.PayloadSafety.acceptableArtifactSize(bytes.length)) {
			return Written.failure("Artifact is " + bytes.length + " bytes; the ceiling is "
				+ dev.champions.core.module.payload.PayloadSafety.maximumArtifactBytes() + ".");
		}
		try {
			Files.createDirectories(directory);
			Path target = directory.resolve(fileName);
			// Belt and braces: the name is sanitised above, and the resolved path is
			// still checked so a platform-specific separator cannot escape.
			if (!target.normalize().startsWith(directory.normalize())) {
				return Written.failure("Refusing to write outside the audit folder.");
			}
			Files.writeString(target, content, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			return new Written(true, "Wrote " + bytes.length + " bytes to " + target, target);
		} catch (IOException error) {
			return Written.failure("Could not write the artifact: " + error.getMessage());
		}
	}

	/** Removes one artifact. Returns false when there was nothing to remove. */
	public boolean delete(String fileName) {
		if (directory == null || fileName == null || fileName.isBlank()) return false;
		try {
			return Files.deleteIfExists(directory.resolve(fileName));
		} catch (IOException error) {
			return false;
		}
	}

	/** Files currently in the folder, newest name order, for the module's evidence. */
	public List<String> list() {
		if (directory == null || !Files.isDirectory(directory)) return List.of();
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
		} catch (IOException error) {
			return List.of();
		}
	}

	public String describe() {
		return directory == null ? "no artifact folder" : directory.toString();
	}

	/** Case-insensitive check used by the module before it claims a name is free. */
	public boolean contains(String fileName) {
		if (fileName == null) return false;
		String wanted = fileName.toLowerCase(Locale.ROOT);
		return list().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(wanted));
	}
}
