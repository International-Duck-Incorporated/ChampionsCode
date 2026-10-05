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

		List<Path> sources = new ArrayList<>();
		try (var stream = Files.walk(root)) {
			stream.filter(path -> isModuleSource(root, path))
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

	/**
	 * True when a path below the drop folder is a module source file. Files are
	 * matched anywhere in the tree, at any depth, so nested module folders work
	 * without configuration. Directories and files whose name starts with a dot
	 * are skipped, which covers the generated {@code .build} directory, editor
	 * backups such as {@code .MyModule.java.swp}, and version-control metadata.
	 */
	private static boolean isModuleSource(Path root, Path path) {
		Path fileName = path.getFileName();
		if (fileName == null || !fileName.toString().endsWith(".java")) return false;
		if (path.startsWith(root.resolve(BUILD_DIRECTORY))) return false;
		Path relative;
		try {
			relative = root.relativize(path);
		} catch (IllegalArgumentException notBelowRoot) {
			return false;
		}
		if (relative.getNameCount() == 0) return false;
		for (Path segment : relative) {
			String name = segment.toString();
			if (name.isEmpty() || name.startsWith(".")) return false;
		}
		return true;
	}

	/**
	 * Cheap metadata-only fingerprint of the drop folder: the recursive relative
	 * paths, sizes, and modification times of every module source file. It reads
	 * no file content, so the plug-and-play watcher can poll it frequently, and
	 * it changes whenever a file is added, removed, renamed, or written.
	 *
	 * <p>An absent folder is reported as {@link FolderFingerprint#ABSENT} instead
	 * of throwing, because a drop folder legitimately may not exist yet.</p>
	 */
	public static FolderFingerprint fingerprint(Path workspace) throws IOException {
		Path root = workspace.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			return new FolderFingerprint(FolderFingerprint.ABSENT, 0, 0L);
		}
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
		List<String> entries = new ArrayList<>();
		int count = 0;
		long bytes = 0;
		try (var stream = Files.walk(root)) {
			var iterator = stream.filter(path -> isModuleSource(root, path))
				.sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
				.iterator();
			while (iterator.hasNext()) {
				Path source = iterator.next();
				long size;
				long modified;
				try {
					size = Files.size(source);
					modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis();
				} catch (IOException vanished) {
					// A file that is being rewritten right now just makes the folder unstable,
					// which the quiet period already handles; the next poll sees the final state.
					continue;
				}
				String relative = root.relativize(source).toString().replace('\\', '/');
				entries.add(relative + ":" + size + ":" + modified);
				count++;
				bytes += size;
			}
		} catch (java.io.UncheckedIOException concurrentChange) {
			return new FolderFingerprint(FolderFingerprint.ABSENT, 0, 0L);
		}
		for (String entry : entries) {
			digest.update(entry.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
		}
		return new FolderFingerprint(HexFormat.of().formatHex(digest.digest()), count, bytes);
	}

	/**
	 * Outcome of {@link #fingerprint(Path)}: an opaque stability token plus the
	 * file count and byte total used for cheap pre-filtering.
	 */
	public record FolderFingerprint(String value, int fileCount, long totalBytes) {
		/** Fingerprint value for a folder that does not exist (or is not a directory). */
		public static final String ABSENT = "absent";

		public FolderFingerprint {
			value = value == null ? ABSENT : value;
		}

		public boolean absent() { return ABSENT.equals(value); }

		/** True when the folder exists and holds no module source files. */
		public boolean empty() { return fileCount == 0; }
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
