package dev.champions.core.log;

import java.lang.reflect.Method;

/**
 * Dependency-free logging facade for the Minecraft-independent half of the mod.
 *
 * <p>Inside the game it delegates to the SLF4J binding Minecraft already ships.
 * Outside the game — the desktop app started with {@code java -jar
 * champions-mod-<version>.jar} — there is no SLF4J on the classpath, so the
 * same calls print to standard error with a timestamp instead of failing to
 * load. Delegation is resolved once, reflectively, and the reflective paths are
 * only used when SLF4J is genuinely present.</p>
 */
public final class Log {
	private static final Backend BACKEND = Backend.resolve();

	private Log() {}

	public static void info(String format, Object... arguments) {
		BACKEND.write("INFO", format, arguments);
	}

	public static void warn(String format, Object... arguments) {
		BACKEND.write("WARN", format, arguments);
	}

	public static void error(String format, Object... arguments) {
		BACKEND.write("ERROR", format, arguments);
	}

	public static void debug(String format, Object... arguments) {
		BACKEND.write("DEBUG", format, arguments);
	}

	private interface Backend {
		void write(String level, String format, Object[] arguments);

		static Backend resolve() {
			try {
				Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
				Object logger = factory.getMethod("getLogger", String.class).invoke(null, "championscode");
				Method info = logger.getClass().getMethod("info", String.class, Object[].class);
				Method warn = logger.getClass().getMethod("warn", String.class, Object[].class);
				Method error = logger.getClass().getMethod("error", String.class, Object[].class);
				Method debug = logger.getClass().getMethod("debug", String.class, Object[].class);
				return (level, format, arguments) -> {
					Method target = switch (level) {
						case "WARN" -> warn;
						case "ERROR" -> error;
						case "DEBUG" -> debug;
						default -> info;
					};
					try {
						target.invoke(logger, format, arguments);
					} catch (ReflectiveOperationException | RuntimeException ignored) {
						stderr(level, format, arguments);
					}
				};
			} catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
				return Backend::stderr;
			}
		}

		static void stderr(String level, String format, Object[] arguments) {
			System.err.println("[" + level + "] " + format(format, arguments));
		}
	}

	/** {@code {}} placeholder substitution, matching the SLF4J convention used across the code base. */
	private static String format(String format, Object[] arguments) {
		if (format == null) return "";
		if (arguments == null || arguments.length == 0) return format;
		StringBuilder out = new StringBuilder(format.length() + 32);
		int argument = 0;
		for (int index = 0; index < format.length(); index++) {
			char current = format.charAt(index);
			if (current == '{' && index + 1 < format.length() && format.charAt(index + 1) == '}'
					&& argument < arguments.length) {
				out.append(arguments[argument++]);
				index++;
			} else {
				out.append(current);
			}
		}
		while (argument < arguments.length) out.append(' ').append(arguments[argument++]);
		return out.toString();
	}
}
