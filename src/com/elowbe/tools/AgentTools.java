package com.elowbe.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

public class AgentTools {
	private static final int MAX_OUTPUT_CHARS = 40_000;
	private static final Duration BASH_TIMEOUT = Duration.ofSeconds(60);

	private AgentTools() {
	}

	public static ToolResult execute(String name, JSONObject arguments, File workingDirectory) {
		if (name == null || name.isBlank()) {
			return ToolResult.output("Tool error: missing tool name");
		}
		if (arguments == null) {
			arguments = new JSONObject();
		}

		try {
			return switch (name) {
			case "read" -> read(arguments, workingDirectory);
			case "bash" -> bash(arguments, workingDirectory);
			case "edit" -> edit(arguments, workingDirectory);
			case "write" -> write(arguments, workingDirectory);
			case "done" -> done(arguments);
			default -> ToolResult.output("Tool error: unknown tool: " + name);
			};
		} catch (Exception e) {
			return ToolResult.output("Tool error: " + e.getMessage());
		}
	}

	private static ToolResult read(JSONObject arguments, File workingDirectory) throws IOException {
		File file = resolvePath(first(arguments, "path", "file"), workingDirectory);
		if (!file.isFile()) {
			return ToolResult.output("read: not a file: " + file.getPath());
		}
		String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		return ToolResult.output(truncate(content));
	}

	private static ToolResult bash(JSONObject arguments, File workingDirectory) throws IOException, InterruptedException {
		String command = first(arguments, "command", "cmd");
		if (command.isBlank()) {
			return ToolResult.output("bash: missing command");
		}

		File cwd = workingDirectory;
		if (cwd == null) {
			cwd = new File(System.getProperty("user.dir"));
		}
		if (!cwd.exists()) {
			cwd.mkdirs();
		}

		Process process = new ProcessBuilder("/bin/zsh", "-lc", command)
				.directory(cwd)
				.start();

		StreamCollector stdout = new StreamCollector(process.getInputStream());
		StreamCollector stderr = new StreamCollector(process.getErrorStream());
		Thread outThread = new Thread(stdout, "elowbe-tool-stdout");
		Thread errThread = new Thread(stderr, "elowbe-tool-stderr");
		outThread.start();
		errThread.start();

		boolean completed = process.waitFor(BASH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		if (!completed) {
			process.destroyForcibly();
		}

		outThread.join();
		errThread.join();

		StringBuilder result = new StringBuilder();
		if (!completed) {
			result.append("Timed out after ").append(BASH_TIMEOUT.toSeconds()).append(" seconds\n");
		}
		result.append("exit_code: ").append(completed ? process.exitValue() : "timeout").append('\n');
		if (!stdout.text().isBlank()) {
			result.append("stdout:\n").append(stdout.text()).append('\n');
		}
		if (!stderr.text().isBlank()) {
			result.append("stderr:\n").append(stderr.text()).append('\n');
		}
		return ToolResult.output(truncate(result.toString().trim()));
	}

	private static ToolResult edit(JSONObject arguments, File workingDirectory) throws IOException {
		File file = resolvePath(first(arguments, "path", "file"), workingDirectory);
		String oldText = first(arguments, "old", "old_text", "target");
		String newText = first(arguments, "new", "new_text", "replacement");
		if (oldText.isEmpty()) {
			return ToolResult.output("edit: missing old text");
		}
		if (!file.isFile()) {
			return ToolResult.output("edit: not a file: " + file.getPath());
		}

		Path path = file.toPath();
		String content = Files.readString(path, StandardCharsets.UTF_8);
		int first = content.indexOf(oldText);
		if (first < 0) {
			return ToolResult.output("edit: old text not found");
		}
		if (content.indexOf(oldText, first + oldText.length()) >= 0) {
			return ToolResult.output("edit: old text is ambiguous; it appears more than once");
		}

		String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
		Files.writeString(path, updated, StandardCharsets.UTF_8);
		return ToolResult.output("edit: updated " + file.getPath());
	}

	private static ToolResult write(JSONObject arguments, File workingDirectory) throws IOException {
		File file = resolvePath(first(arguments, "path", "file"), workingDirectory);
		String content = first(arguments, "content", "text");
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
		return ToolResult.output("write: wrote " + file.getPath());
	}

	private static ToolResult done(JSONObject arguments) {
		String response = first(arguments, "response", "message", "summary");
		String output = response.isBlank() ? "done" : response;
		return ToolResult.complete(output, response);
	}

	private static File resolvePath(String value, File workingDirectory) throws IOException {
		if (value == null || value.isBlank()) {
			throw new IOException("missing path");
		}
		File file = new File(stripQuotes(value.trim()));
		if (!file.isAbsolute()) {
			File base = workingDirectory == null ? new File(System.getProperty("user.dir")) : workingDirectory;
			file = new File(base, file.getPath());
		}
		return file.getCanonicalFile();
	}

	private static String first(JSONObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key));
			}
		}
		return "";
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2
				&& ((value.startsWith("\"") && value.endsWith("\""))
						|| (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static String truncate(String text) {
		if (text.length() <= MAX_OUTPUT_CHARS) {
			return text;
		}
		return text.substring(0, MAX_OUTPUT_CHARS) + "\n... truncated ...";
	}

	private static class StreamCollector implements Runnable {
		private final InputStream input;
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		private StreamCollector(InputStream input) {
			this.input = input;
		}

		@Override
		public void run() {
			try (InputStream stream = input) {
				stream.transferTo(output);
			} catch (IOException e) {
				output.writeBytes(("<stream error: " + e.getMessage() + ">").getBytes(StandardCharsets.UTF_8));
			}
		}

		private String text() {
			return output.toString(StandardCharsets.UTF_8);
		}
	}
}
