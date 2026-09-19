package dev.champions.core.script;

import dev.champions.core.console.ConsoleLine;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A deliberately non-Turing-complete, read-only client runtime. It has no APIs
 * for files, networking, reflection, native code, packets, chat, or game mutation.
 */
public final class ChampionScriptEngine {
	public static final int MAX_SOURCE_CHARS = 16 * 1024;
	public static final int MAX_STATEMENTS = 256;
	public static final int MAX_OUTPUT_CHARS = 8 * 1024;
	private static final long MAX_NANOS = 50_000_000L;
	private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
	private final ModuleRegistry modules;

	public ChampionScriptEngine(ModuleRegistry modules) {
		this.modules = modules;
	}

	public ScriptResult execute(String source, ModuleContext context) {
		if (source == null) return ScriptResult.failed("No script supplied.");
		if (source.length() > MAX_SOURCE_CHARS) return ScriptResult.failed("Source exceeds the 16 KiB limit.");
		List<String> statements;
		try { statements = splitStatements(source); }
		catch (IllegalArgumentException e) { return ScriptResult.failed(e.getMessage()); }
		if (statements.size() > MAX_STATEMENTS) return ScriptResult.failed("Script exceeds the 256-statement limit.");

		long deadline = System.nanoTime() + MAX_NANOS;
		int outputChars = 0;
		Map<String, String> variables = new HashMap<>();
		List<ConsoleLine> output = new ArrayList<>();
		for (int i = 0; i < statements.size(); i++) {
			if (System.nanoTime() > deadline) return new ScriptResult(false, i, output, "Execution time budget exceeded.");
			String statement = statements.get(i).strip();
			if (statement.isEmpty()) continue;
			try {
				List<ConsoleLine> next = executeStatement(statement, context, variables);
				for (ConsoleLine line : next) outputChars += line.text().length();
				if (outputChars > MAX_OUTPUT_CHARS) return new ScriptResult(false, i, output, "Output budget exceeded.");
				output.addAll(next);
			} catch (ScriptException e) {
				return new ScriptResult(false, i + 1, output, "Line " + (i + 1) + ": " + e.getMessage());
			}
		}
		return new ScriptResult(true, statements.size(), List.copyOf(output), "Completed");
	}

	private List<ConsoleLine> executeStatement(String statement, ModuleContext c, Map<String, String> vars) throws ScriptException {
		String lower = statement.toLowerCase(Locale.ROOT);
		if (lower.equals("help")) return List.of(
			ConsoleLine.accent("ChampionScript: print, let, get, status, server, player, position, modules, assert"),
			ConsoleLine.muted("Expressions: quoted text, $variable, runtime values, and + concatenation."));
		if (lower.equals("status")) return List.of(
			ConsoleLine.info("connected=" + c.connected() + " fps=" + c.clientFps()),
			ConsoleLine.info("server=" + c.serverAddress() + " brand=" + c.serverBrand()),
			ConsoleLine.info("player=" + c.playerName() + " position=" + c.playerPosition()));
		if (lower.equals("server")) return List.of(ConsoleLine.info(c.serverAddress() + " | " + c.serverBrand() + " | " + c.advertisedVersion()));
		if (lower.equals("player")) return List.of(ConsoleLine.info(c.playerName() + " @ " + c.dimension()));
		if (lower.equals("position")) return List.of(ConsoleLine.info(c.playerPosition()));
		if (lower.equals("modules")) return modules.all().stream().map(m -> ConsoleLine.info(m.id())).toList();
		if (lower.startsWith("print ")) return List.of(ConsoleLine.info(evaluate(statement.substring(6), c, vars)));
		if (lower.startsWith("notify ")) return List.of(ConsoleLine.accent("[notify] " + evaluate(statement.substring(7), c, vars)));
		if (lower.startsWith("get ")) {
			String name = statement.substring(4).strip();
			if (!vars.containsKey(name)) throw new ScriptException("Unknown variable: " + name);
			return List.of(ConsoleLine.info(vars.get(name)));
		}
		if (lower.startsWith("let ")) {
			int equals = statement.indexOf('=');
			if (equals < 0) throw new ScriptException("Expected: let name = expression");
			String name = statement.substring(4, equals).strip();
			if (!VARIABLE.matcher(name).matches()) throw new ScriptException("Invalid variable name: " + name);
			String value = evaluate(statement.substring(equals + 1), c, vars);
			vars.put(name, value);
			return List.of(ConsoleLine.muted(name + " = " + value));
		}
		if (lower.startsWith("assert ")) {
			String condition = lower.substring(7).strip();
			boolean ok = switch (condition) {
				case "connected" -> c.connected();
				case "disconnected" -> !c.connected();
				case "online_mode" -> c.onlineMode();
				default -> throw new ScriptException("Unknown assertion: " + condition);
			};
			if (!ok) throw new ScriptException("Assertion failed: " + condition);
			return List.of(ConsoleLine.success("assert " + condition + " passed"));
		}
		throw new ScriptException("Unknown statement: " + firstWord(statement));
	}

	private String evaluate(String expression, ModuleContext c, Map<String, String> vars) throws ScriptException {
		List<String> parts = splitExpression(expression);
		StringBuilder out = new StringBuilder();
		for (String raw : parts) {
			String term = raw.strip();
			if (term.isEmpty()) throw new ScriptException("Empty expression term.");
			if ((term.startsWith("\"") && term.endsWith("\"")) || (term.startsWith("'") && term.endsWith("'"))) {
				out.append(unescape(term.substring(1, term.length() - 1)));
			} else if (term.startsWith("$")) {
				String name = term.substring(1);
				if (!vars.containsKey(name)) throw new ScriptException("Unknown variable: " + name);
				out.append(vars.get(name));
			} else {
				out.append(runtimeValue(term.toLowerCase(Locale.ROOT), c));
			}
		}
		return limit(out.toString(), 1024);
	}

	private static String runtimeValue(String name, ModuleContext c) throws ScriptException {
		return switch (name) {
			case "server.address" -> c.serverAddress();
			case "server.brand" -> c.serverBrand();
			case "server.version" -> c.advertisedVersion();
			case "server.protocol" -> Integer.toString(c.advertisedProtocol());
			case "server.players" -> Integer.toString(c.onlinePlayers());
			case "server.ping" -> Long.toString(c.pingMillis());
			case "player.name" -> c.playerName();
			case "player.position" -> c.playerPosition();
			case "player.dimension" -> c.dimension();
			case "client.connected" -> Boolean.toString(c.connected());
			case "client.fps" -> Integer.toString(c.clientFps());
			case "true", "false" -> name;
			default -> throw new ScriptException("Unknown value: " + name);
		};
	}

	private static List<String> splitStatements(String source) {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;
		boolean escaped = false;
		boolean comment = false;
		for (int i = 0; i < source.length(); i++) {
			char ch = source.charAt(i);
			if (comment) {
				if (ch == '\n') { comment = false; addStatement(result, current); }
				continue;
			}
			if (escaped) { current.append(ch); escaped = false; continue; }
			if (ch == '\\' && quote != 0) { current.append(ch); escaped = true; continue; }
			if (quote != 0) {
				current.append(ch);
				if (ch == quote) quote = 0;
				continue;
			}
			if (ch == '\'' || ch == '"') { quote = ch; current.append(ch); }
			else if (ch == '#') comment = true;
			else if (ch == ';' || ch == '\n' || ch == '\r') addStatement(result, current);
			else current.append(ch);
		}
		if (quote != 0) throw new IllegalArgumentException("Unclosed quote in script.");
		addStatement(result, current);
		return result;
	}

	private static List<String> splitExpression(String expression) throws ScriptException {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;
		boolean escaped = false;
		for (int i = 0; i < expression.length(); i++) {
			char ch = expression.charAt(i);
			if (escaped) { current.append(ch); escaped = false; continue; }
			if (ch == '\\' && quote != 0) { current.append(ch); escaped = true; continue; }
			if (quote != 0) {
				current.append(ch);
				if (ch == quote) quote = 0;
			} else if (ch == '\'' || ch == '"') {
				quote = ch; current.append(ch);
			} else if (ch == '+') {
				result.add(current.toString()); current.setLength(0);
			} else current.append(ch);
		}
		if (quote != 0) throw new ScriptException("Unclosed quote in expression.");
		result.add(current.toString());
		return result;
	}

	private static void addStatement(List<String> out, StringBuilder value) {
		String line = value.toString().strip();
		if (!line.isEmpty()) out.add(line);
		value.setLength(0);
	}

	private static String unescape(String input) {
		return input.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
	}

	private static String firstWord(String input) {
		int space = input.indexOf(' ');
		return space < 0 ? input : input.substring(0, space);
	}

	private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }

	private static final class ScriptException extends Exception {
		private ScriptException(String message) { super(message); }
	}

	public record ScriptResult(boolean success, int statementsExecuted, List<ConsoleLine> output, String message) {
		private static ScriptResult failed(String message) { return new ScriptResult(false, 0, List.of(), message); }
	}
}
