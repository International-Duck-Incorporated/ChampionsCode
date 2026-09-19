package dev.champions.core.java;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Immutable, deterministically hashed runtime Java source set. */
public record JavaSourceSet(Path workspace, Path manifestFile, JavaAddonManifest manifest, List<Path> sources, String sha256, long totalBytes) {
	public static final int MAX_SOURCE_FILES = 128;
	public static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L;

	public JavaSourceSet {
		workspace = workspace.toAbsolutePath().normalize();
		manifestFile = manifestFile.toAbsolutePath().normalize();
		sources = List.copyOf(sources);
	}

	public static JavaSourceSet capture(Path workspace) throws IOException {
		Path root = workspace.toAbsolutePath().normalize();
		Path manifestPath = root.resolve("champion-java.json");
		JavaAddonManifest manifest = JavaAddonManifest.read(manifestPath);
		List<Path> sources = new ArrayList<>();
		try (var stream = Files.walk(root)) {
			stream.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.startsWith(root.resolve(".build")))
				.sorted(Comparator.comparing(path -> root.relativize(path).toString()))
				.forEach(sources::add);
		}
		if (sources.isEmpty()) throw new IOException("No .java source files found in " + root);
		if (sources.size() > MAX_SOURCE_FILES) throw new IOException("Source set exceeds " + MAX_SOURCE_FILES + " Java files");

		long total = Files.size(manifestPath);
		for (Path source : sources) {
			if (Files.isSymbolicLink(source) || !Files.isRegularFile(source)) throw new IOException("Source must be a regular non-symlink file: " + source);
			total = Math.addExact(total, Files.size(source));
			if (total > MAX_TOTAL_BYTES) throw new IOException("Source set exceeds the 2 MiB limit");
		}

		MessageDigest digest;
		try { digest = MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
		update(digest, root, manifestPath);
		for (Path source : sources) update(digest, root, source);
		return new JavaSourceSet(root, manifestPath, manifest, sources, HexFormat.of().formatHex(digest.digest()), total);
	}

	private static void update(MessageDigest digest, Path root, Path file) throws IOException {
		String relative = root.relativize(file).toString().replace('\\', '/');
		byte[] name = relative.getBytes(StandardCharsets.UTF_8);
		byte[] content = Files.readAllBytes(file);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
		digest.update(name);
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
		digest.update(content);
	}
}
