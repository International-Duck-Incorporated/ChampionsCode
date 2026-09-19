package dev.champions.core.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Sandboxed script storage: only simple names under config/champions-mod/scripts. */
public final class ScriptRepository {
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final int MAX_BYTES = 16 * 1024;
	private final Path directory;

	public ScriptRepository(Path configDirectory) {
		this.directory = configDirectory.resolve("scripts").normalize();
		initialize();
	}

	public List<String> list() throws IOException {
		try (var stream = Files.list(directory)) {
			return stream.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".champ"))
				.map(name -> name.substring(0, name.length() - 6))
				.sorted().toList();
		}
	}

	public String read(String name) throws IOException {
		Path path = resolve(name);
		long size = Files.size(path);
		if (size > MAX_BYTES) throw new IOException("Script exceeds the 16 KiB limit.");
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	public Path directory() { return directory; }

	private Path resolve(String name) throws IOException {
		if (name == null || !SAFE_NAME.matcher(name).matches()) throw new IOException("Invalid script name.");
		Path path = directory.resolve(name + ".champ").normalize();
		if (!path.startsWith(directory)) throw new IOException("Script path escaped the sandbox.");
		if (!Files.isRegularFile(path)) throw new IOException("Script not found: " + name);
		return path;
	}

	private void initialize() {
		try {
			Files.createDirectories(directory);
			Path demo = directory.resolve("demo.champ");
			if (Files.notExists(demo)) {
				Files.writeString(demo, """
					# Read-only ChampionScript demo
					print "Champions runtime demo"
					status
					let target = server.address
					print "Current target: " + $target
					assert connected
					modules
					""", StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not initialize script directory", e);
		}
	}
}
