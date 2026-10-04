package dev.champions.core.java;

import dev.champions.core.module.ResearchModule;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Discovers top-level ResearchModule implementations in a compiled drop-in bundle. */
public final class RuntimeModuleDiscovery {
	private RuntimeModuleDiscovery() {}

	public static List<Class<? extends ResearchModule>> discover(Path classesDirectory, ClassLoader loader) throws IOException {
		if (loader == null) throw new IllegalArgumentException("Module classloader is required");
		if (!Files.isDirectory(classesDirectory)) throw new IOException("Compiled module directory is missing: " + classesDirectory);

		List<Path> classFiles;
		try (var stream = Files.walk(classesDirectory)) {
			classFiles = stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".class"))
				.filter(path -> !path.getFileName().toString().equals("module-info.class"))
				.filter(path -> !path.getFileName().toString().equals("package-info.class"))
				.filter(path -> !path.getFileName().toString().contains("$"))
				.sorted(Comparator.comparing(path -> classesDirectory.relativize(path).toString().replace('\\', '/')))
				.toList();
		}

		List<Class<? extends ResearchModule>> modules = new ArrayList<>();
		for (Path classFile : classFiles) {
			String className = classesDirectory.relativize(classFile).toString()
				.replace('\\', '/').replace('/', '.');
			className = className.substring(0, className.length() - ".class".length());
			Class<?> type;
			try {
				type = Class.forName(className, false, loader);
			} catch (ClassNotFoundException | LinkageError | SecurityException error) {
				throw new IOException("Could not inspect compiled class " + className + ": " + safeMessage(error), error);
			}
			if (!ResearchModule.class.isAssignableFrom(type)) continue;
			if (type.getClassLoader() != loader) {
				throw new IOException("Research module class is shadowed by a class already on the game classpath: " + className);
			}
			if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
			if (!Modifier.isPublic(type.getModifiers())) {
				throw new IOException("Research module class must be public: " + className);
			}
			try {
				Constructor<?> constructor = type.getConstructor();
				if (!Modifier.isPublic(constructor.getModifiers())) {
					throw new IOException("Research module needs a public no-argument constructor: " + className);
				}
			} catch (NoSuchMethodException error) {
				throw new IOException("Research module needs a public no-argument constructor: " + className, error);
			}
			modules.add(type.asSubclass(ResearchModule.class));
		}
		if (modules.isEmpty()) {
			throw new IOException("No concrete top-level ResearchModule implementations were found in the compiled sources");
		}
		return List.copyOf(modules);
	}

	private static String safeMessage(Throwable error) {
		String message = error.getMessage();
		if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
		message = message.replace('\r', ' ').replace('\n', ' ');
		return message.length() <= 300 ? message : message.substring(0, 300) + "...";
	}
}
