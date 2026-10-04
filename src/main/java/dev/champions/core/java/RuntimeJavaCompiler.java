package dev.champions.core.java;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Thin wrapper around the full JDK 25 compiler. It does not execute compiled classes. */
public final class RuntimeJavaCompiler {
	private RuntimeJavaCompiler() {}

	public static boolean available() {
		return ToolProvider.getSystemJavaCompiler() != null;
	}

	public static CompilationResult compile(JavaSourceSet sourceSet, Path outputDirectory, List<Path> classpath) throws IOException {
		return compile(sourceSet.sources(), outputDirectory, classpath);
	}

	/** Compiles a previously captured source list, such as an external module bundle. */
	public static CompilationResult compile(List<Path> sources, Path outputDirectory, List<Path> classpath) throws IOException {
		if (sources == null || sources.isEmpty()) {
			return new CompilationResult(false, List.of("No Java source files were supplied."));
		}
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return new CompilationResult(false, List.of("No system Java compiler is available. Launch Minecraft with a full JDK 25 installation."));
		}
		Files.createDirectories(outputDirectory);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
			List<String> options = new ArrayList<>();
			options.add("--release");
			options.add("25");
			options.add("-encoding");
			options.add("UTF-8");
			options.add("-proc:none");
			options.add("-d");
			options.add(outputDirectory.toString());
			if (!classpath.isEmpty()) {
				options.add("-classpath");
				options.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
			}
			boolean success = Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics, options, null, units).call());
			return new CompilationResult(success, formatDiagnostics(diagnostics.getDiagnostics()));
		}
	}

	private static List<String> formatDiagnostics(List<Diagnostic<? extends JavaFileObject>> values) {
		List<String> result = new ArrayList<>();
		for (Diagnostic<? extends JavaFileObject> diagnostic : values) {
			String source = diagnostic.getSource() == null ? "<unknown>" : Path.of(diagnostic.getSource().toUri()).getFileName().toString();
			String message = diagnostic.getMessage(Locale.ROOT).replace('\n', ' ').replace('\r', ' ');
			if (message.length() > 500) message = message.substring(0, 500) + "...";
			result.add(diagnostic.getKind() + " " + source + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + message);
			if (result.size() >= 100) {
				result.add("Additional compiler diagnostics were omitted.");
				break;
			}
		}
		return List.copyOf(result);
	}

	public record CompilationResult(boolean success, List<String> diagnostics) {
		public CompilationResult {
			diagnostics = List.copyOf(diagnostics);
		}
	}
}
