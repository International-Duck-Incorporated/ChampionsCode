package dev.champions.core.java;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Immutable, bounded, deterministically hashed source set for external research modules. */
public record RuntimeModuleSourceSet(Path workspace, List<Path> sources, String sha256, long totalBytes) {
	public static final int MAX_SOURCE_FILES = 128;
	public static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L;
	private static final String BUILD_DIRECTORY = ".build";

	public RuntimeModuleSourceSet {
		workspace = workspace.toAbsolutePath().normalize();
		sources = List.copyOf(sources);
	}

	/**
	 * Recursively captures *.java files below the configured module directory.
	 * Generated files beneath the root .build directory are never part of the
	 * source set. An empty directory is valid and has a stable hash.
	 */
	public static RuntimeModuleSourceSet capture(Path workspace) throws IOException {
		Path root = workspace.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Module workspace must be a regular directory: " + root);
		}

		Path buildDirectory = root.resolve(BUILD_DIRECTORY);
		List<Path> sources = new ArrayList<>();
		try (var stream = Files.walk(root)) {
			stream.filter(path -> !path.startsWith(buildDirectory))
				.filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".java"))
				.sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
				.forEach(sources::add);
		}
		if (sources.size() > MAX_SOURCE_FILES) {
			throw new IOException("Module source set exceeds " + MAX_SOURCE_FILES + " Java files");
		}

		long total = 0;
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
		for (Path source : sources) {
			if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Module source must be a regular non-symlink file: " + source);
			}
			long remaining = MAX_TOTAL_BYTES - total;
			if (Files.size(source) > remaining) throw new IOException("Module source set exceeds the 2 MiB limit");
			byte[] content;
			try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
				content = input.readNBytes(Math.toIntExact(remaining + 1));
			}
			if (content.length > remaining) throw new IOException("Module source set exceeds the 2 MiB limit");
			total = Math.addExact(total, content.length);
			update(digest, root, source, content);
		}
		return new RuntimeModuleSourceSet(root, sources, HexFormat.of().formatHex(digest.digest()), total);
	}

	private static void update(MessageDigest digest, Path root, Path file, byte[] content) {
		String relative = root.relativize(file).toString().replace('\\', '/');
		byte[] name = relative.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
		digest.update(name);
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
		digest.update(content);
	}
}
