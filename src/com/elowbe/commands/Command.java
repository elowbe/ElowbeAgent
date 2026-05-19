package com.elowbe.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed command line: {@code name -flag value -other "quoted value" -bool}.
 * Options and flags may appear in any order. A token starting with {@code -} is an
 * option; if the next token is not another option, it becomes that option's value,
 * otherwise the option is a boolean flag.
 */
public class Command {
	private final String name;
	private final Map<String, String> options;
	private final Set<String> flags;

	private Command(String name, Map<String, String> options, Set<String> flags) {
		this.name = name;
		this.options = options;
		this.flags = flags;
	}

	public static Command parse(String line) {
		if (line == null) {
			throw new IllegalArgumentException("Command cannot be null");
		}
		line = line.trim();
		if (line.isEmpty()) {
			throw new IllegalArgumentException("Command cannot be empty");
		}

		List<String> tokens = tokenize(line);
		if (tokens.isEmpty()) {
			throw new IllegalArgumentException("Command cannot be empty");
		}

		String commandName = tokens.get(0);
		Map<String, String> options = new LinkedHashMap<>();
		Set<String> flagSet = new LinkedHashSet<>();

		for (int i = 1; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (!token.startsWith("-") || token.length() == 1) {
				throw new IllegalArgumentException("Unexpected token: " + token);
			}

			String key = token.substring(1);
			if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) {
				options.put(key, tokens.get(i + 1));
				i++;
			} else {
				flagSet.add(key);
			}
		}

		return new Command(commandName, options, flagSet);
	}

	public String getName() {
		return name;
	}

	public boolean has(String key) {
		return flags.contains(key) || options.containsKey(key);
	}

	public boolean isFlag(String key) {
		return flags.contains(key);
	}

	public String get(String key) {
		return options.get(key);
	}

	public String get(String key, String defaultValue) {
		String value = options.get(key);
		return value != null ? value : defaultValue;
	}

	public Map<String, String> getOptions() {
		return Collections.unmodifiableMap(options);
	}

	public Set<String> getFlags() {
		return Collections.unmodifiableSet(flags);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(name);
		for (Map.Entry<String, String> entry : options.entrySet()) {
			sb.append(" -").append(entry.getKey()).append(' ').append(quoteIfNeeded(entry.getValue()));
		}
		for (String flag : flags) {
			sb.append(" -").append(flag);
		}
		return sb.toString();
	}

	private static String quoteIfNeeded(String value) {
		if (value.indexOf(' ') >= 0 || value.indexOf('"') >= 0) {
			return '"' + value.replace("\"", "\\\"") + '"';
		}
		return value;
	}

	static List<String> tokenize(String command) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		char quoteChar = 0;
		boolean justClosedQuotedToken = false;

		for (int i = 0; i < command.length(); i++) {
			char c = command.charAt(i);

			if (!inQuotes) {
				if (c == '"' || c == '\'') {
					inQuotes = true;
					quoteChar = c;
				} else if (Character.isWhitespace(c)) {
					if (current.length() > 0 || justClosedQuotedToken) {
						parts.add(current.toString());
						current.setLength(0);
						justClosedQuotedToken = false;
					}
				} else {
					current.append(c);
				}
			} else if (c == quoteChar) {
				inQuotes = false;
				quoteChar = 0;
				justClosedQuotedToken = true;
			} else {
				current.append(c);
			}
		}

		if (inQuotes) {
			throw new IllegalArgumentException("Unclosed quote in command");
		}

		if (current.length() > 0 || justClosedQuotedToken) {
			parts.add(current.toString());
		}

		return parts;
	}
}
