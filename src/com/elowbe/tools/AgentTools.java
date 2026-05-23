package com.elowbe.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;

import com.elowbe.agent.AgentRunner;
import com.elowbe.main.AgentSettings;
import com.elowbe.tools.maven.MavenTool;

public class AgentTools {
	private static final int MAX_OUTPUT_CHARS = 40_000;
	private static final int RUN_HEAD_CHARS = 18_000;
	private static final int RUN_TAIL_CHARS = 18_000;
	private static final Duration DEFAULT_BASH_TIMEOUT = Duration.ofSeconds(60);
	private static final BooleanSupplier NEVER_CANCEL = () -> false;
	private static final Set<ManagedProcess> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();
	private static final Path DEBUG_LOG_PATH = Path
			.of("/Users/kingroka/eclipse-workspace/ElowbeAgent/src/.cursor/debug-b8f46f.log");
	private static final String BASH_WRAPPER = """
			emulate -L zsh
			setopt no_monitor 2>/dev/null || true

			cleanup_agent_jobs() {
			  trap - EXIT HUP INT TERM
			  local -a job_pids
			  job_pids=("${(@f)$(jobs -pr 2>/dev/null)}")
			  if (( ${#job_pids[@]} )); then
			    kill -TERM $job_pids 2>/dev/null || true
			    sleep 0.2
			    job_pids=("${(@f)$(jobs -pr 2>/dev/null)}")
			    if (( ${#job_pids[@]} )); then
			      kill -KILL $job_pids 2>/dev/null || true
			    fi
			    wait 2>/dev/null || true
			  fi
			}

			disown() {
			  print -u2 "disown is disabled for managed agent commands"
			  return 1
			}

			trap 'cleanup_agent_jobs; exit 130' HUP INT TERM
			trap 'cleanup_agent_jobs' EXIT

			eval "$1"
			status=$?
			cleanup_agent_jobs
			exit $status
			""";

	private AgentTools() {
	}

	public static ToolResult execute(String name, JSONObject arguments, File workingDirectory) {
		return execute(name, arguments, workingDirectory, NEVER_CANCEL);
	}

	public static ToolResult execute(String name, JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) {
		return execute(name, arguments, workingDirectory, cancelRequested, 0);
	}

	public static ToolResult execute(String name, JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested, int subtaskDepth) {
		if (name == null || name.isBlank()) {
			return ToolResult.output("Tool error: missing tool name");
		}
		if (arguments == null) {
			arguments = new JSONObject();
		}
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}

		try {
			return switch (name) {
			case "read" -> read(arguments, workingDirectory);
			case "bash" -> bash(arguments, workingDirectory, cancelRequested);
			case "run" -> run(arguments, workingDirectory, cancelRequested);
			case "edit" -> edit(arguments, workingDirectory);
			case "write" -> write(arguments, workingDirectory);
			case "maven" -> MavenTool.execute(arguments, workingDirectory, cancelRequested);
			case "subtask" -> subtask(arguments, workingDirectory, cancelRequested, subtaskDepth);
			case "done" -> done(arguments);
			default -> ToolResult.output("Tool error: unknown tool: " + name);
			};
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ToolResult.output("Tool error: interrupted");
		} catch (Exception e) {
			return ToolResult.output("Tool error: " + e.getMessage());
		}
	}

	public static void cancelActiveProcesses() {
		for (ManagedProcess process : ACTIVE_PROCESSES) {
			process.destroy();
		}
	}

	private static ToolResult read(JSONObject arguments, File workingDirectory) throws IOException {
		File file = resolvePath(first(arguments, "path", "file"), workingDirectory);
		if (!file.isFile()) {
			return ToolResult.output("read: not a file: " + file.getPath());
		}
		String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		return ToolResult.output(truncate(formatWithLineNumbers(content)));
	}

	private static ToolResult bash(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException, InterruptedException {
		String command = first(arguments, "command", "cmd");
		if (command.isBlank()) {
			return ToolResult.output("bash: missing command");
		}
		// #region agent log
		appendDebugLog("pre-fix", "H3", "AgentTools.java:bash:command", "About to execute managed bash command",
				new JSONObject().put("command", command)
						.put("workingDirectory", workingDirectory == null ? "(null)" : workingDirectory.getPath()));
		// #endregion
		Optional<Duration> timeout = resolveBashTimeout(arguments);
		if (cancelRequested.getAsBoolean()) {
			return ToolResult.output("bash: cancelled");
		}

		File cwd = workingDirectory;
		if (cwd == null) {
			cwd = new File(System.getProperty("user.dir"));
		}
		if (!cwd.exists()) {
			cwd.mkdirs();
		}

		Process process = new ProcessBuilder("/bin/zsh", "-lc", BASH_WRAPPER, "elowbe-managed-bash", command)
				.directory(cwd)
				.start();
		ManagedProcess managedProcess = new ManagedProcess(process);
		ACTIVE_PROCESSES.add(managedProcess);

		StreamCollector stdout = new StreamCollector(process.getInputStream());
		StreamCollector stderr = new StreamCollector(process.getErrorStream());
		Thread outThread = new Thread(stdout, "elowbe-tool-stdout");
		Thread errThread = new Thread(stderr, "elowbe-tool-stderr");
		outThread.start();
		errThread.start();

		boolean completed = false;
		boolean timedOut = false;
		boolean cancelled = false;
		Long deadline = timeout.map(value -> System.nanoTime() + value.toNanos()).orElse(null);
		try {
			while (true) {
				managedProcess.rememberDescendants();
				if (cancelRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
					cancelled = true;
					managedProcess.destroy();
					break;
				}
				if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
					completed = true;
					break;
				}
				if (deadline != null && System.nanoTime() >= deadline) {
					timedOut = true;
					managedProcess.destroy();
					break;
				}
			}
		} finally {
			managedProcess.rememberDescendants();
			managedProcess.destroy();
			ACTIVE_PROCESSES.remove(managedProcess);
		}

		outThread.join();
		errThread.join();

		StringBuilder result = new StringBuilder();
		if (cancelled) {
			result.append("Cancelled\n");
		} else if (timedOut && timeout.isPresent()) {
			result.append("Timed out after ").append(timeout.get().toSeconds())
					.append(" seconds while the process was still alive\n");
		}
		if (!stdout.text().isBlank()) {
			result.append("stdout:\n").append(stdout.text()).append('\n');
		}
		String stderrText = stderr.text().replace("zsh:28: read-only variable: status", "");
		
		if (!stderrText.isBlank()) {
			result.append("stderr:\n").append(stderrText).append('\n');
		}
		// #region agent log
		appendDebugLog("pre-fix", "H1", "AgentTools.java:bash:result", "Managed bash command completed",
				new JSONObject()
						.put("command", command)
						.put("completed", completed)
						.put("timedOut", timedOut)
						.put("cancelled", cancelled)
						.put("stdout", truncate(stdout.text()))
						.put("stderr", truncate(stderr.text()))
						.put("hasReadOnlyStatusError", stderr.text().contains("read-only variable:status")));
		// #endregion
		return ToolResult.output(truncate(result.toString().trim()));
	}

	private static Optional<Duration> resolveBashTimeout(JSONObject arguments) {
		if (arguments == null || !arguments.has("timeout_seconds") || arguments.isNull("timeout_seconds")) {
			return Optional.of(DEFAULT_BASH_TIMEOUT);
		}
		Object raw = arguments.get("timeout_seconds");
		double seconds;
		if (raw instanceof Number number) {
			seconds = number.doubleValue();
		} else {
			try {
				seconds = Double.parseDouble(String.valueOf(raw).trim());
			} catch (NumberFormatException e) {
				return Optional.of(DEFAULT_BASH_TIMEOUT);
			}
		}
		if (!Double.isFinite(seconds) || seconds < 0) {
			return Optional.of(DEFAULT_BASH_TIMEOUT);
		}
		if (seconds == 0) {
			return Optional.empty();
		}
		long millis = Math.max(1L, Math.round(seconds * 1000.0));
		return Optional.of(Duration.ofMillis(millis));
	}

	private static ToolResult run(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException, InterruptedException {
		if (AgentSettings.load().isProjectsRoot(workingDirectory)) {
			return ToolResult.output("run: open a project first; run.sh/run.bat cannot be used in the projects folder");
		}
		String command = first(arguments, "command", "cmd");
		if (command.isBlank()) {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			command = os.contains("win") ? "cmd /c run.bat" : "bash ./run.sh";
		}
		ToolResult raw = bash(new JSONObject().put("command", command), workingDirectory, cancelRequested);
		String output = raw.getOutput();
		StringBuilder visible = new StringBuilder();
		visible.append("run_command: ").append(command).append('\n');
		visible.append(formatRunOutput(output));
		return ToolResult.output(visible.toString());
	}

	private static ToolResult subtask(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested,
			int subtaskDepth) throws IOException {
		if (subtaskDepth > 0) {
			return ToolResult.output("subtask: nested subtasks are disabled; finish this worker task directly");
		}
		String task = first(arguments, "task", "instruction", "goal");
		if (task.isBlank()) {
			return ToolResult.output("subtask: missing task");
		}
		String context = first(arguments, "context", "summary");
		String result = AgentRunner.runSubtask(task, context, workingDirectory, cancelRequested);
		return ToolResult.output("subtask result:\n" + truncate(result));
	}

	private static ToolResult edit(JSONObject arguments, File workingDirectory) throws IOException {
		File file = resolvePath(first(arguments, "path", "file"), workingDirectory);
		int startLine = firstInt(arguments, 0, "start_line", "start");
		int endLine = firstInt(arguments, 0, "end_line", "end");
		String newText = first(arguments, "new", "new_text", "replacement", "content");
		if (startLine < 1) {
			return ToolResult.output("edit: missing or invalid start_line (must be >= 1)");
		}
		if (endLine < startLine - 1) {
			return ToolResult.output("edit: end_line must be >= start_line - 1");
		}
		if (!file.isFile()) {
			return ToolResult.output("edit: not a file: " + file.getPath());
		}

		Path path = file.toPath();
		String content = Files.readString(path, StandardCharsets.UTF_8);
		List<String> lines = splitLines(content);
		List<String> replacement = splitNewText(newText);
		boolean insert = endLine == startLine - 1;

		if (insert) {
			if (startLine > lines.size() + 1) {
				return ToolResult.output("edit: start_line " + startLine + " is out of range (file has "
						+ lines.size() + " lines; use " + (lines.size() + 1) + " to append)");
			}
			lines.addAll(startLine - 1, replacement);
		} else {
			if (endLine > lines.size()) {
				return ToolResult.output("edit: end_line " + endLine + " is out of range (file has "
						+ lines.size() + " lines)");
			}
			lines.subList(startLine - 1, endLine).clear();
			lines.addAll(startLine - 1, replacement);
		}

		Files.writeString(path, joinLines(lines, content), StandardCharsets.UTF_8);
		int replaced = insert ? 0 : endLine - startLine + 1;
		int inserted = replacement.size();
		return ToolResult.output("edit: updated " + file.getPath() + " (replaced " + replaced + " line(s) with "
				+ inserted + " line(s))");
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

	private static int firstInt(JSONObject object, int defaultValue, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				Object raw = object.get(key);
				if (raw instanceof Number number) {
					return number.intValue();
				}
				try {
					return Integer.parseInt(String.valueOf(raw).trim());
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return defaultValue;
	}

	private static String formatWithLineNumbers(String content) {
		List<String> lines = splitLines(content);
		if (lines.isEmpty()) {
			return "1|";
		}
		int width = String.valueOf(lines.size()).length();
		StringBuilder numbered = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				numbered.append('\n');
			}
			numbered.append(String.format(Locale.ROOT, "%" + width + "d|%s", i + 1, lines.get(i)));
		}
		return numbered.toString();
	}

	private static List<String> splitLines(String content) {
		if (content.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(content.split("\n", -1)));
	}

	private static List<String> splitNewText(String newText) {
		if (newText == null || newText.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(newText.split("\n", -1)));
	}

	private static String joinLines(List<String> lines, String originalContent) {
		if (lines.isEmpty()) {
			return "";
		}
		String joined = String.join("\n", lines);
		if (!originalContent.isEmpty() && originalContent.endsWith("\n")) {
			return joined + "\n";
		}
		return joined;
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
		if (text == null) {
			return "";
		}
		if (text.length() <= MAX_OUTPUT_CHARS) {
			return text;
		}
		int keepHead = Math.max(1, MAX_OUTPUT_CHARS / 2 - 100);
		int keepTail = Math.max(1, MAX_OUTPUT_CHARS - keepHead - 150);
		int omitted = text.length() - keepHead - keepTail;
		return text.substring(0, keepHead)
				+ "\n... truncated " + omitted + " chars ...\n"
				+ text.substring(text.length() - keepTail);
	}

	private static String formatRunOutput(String rawOutput) {
		if (rawOutput == null || rawOutput.isBlank()) {
			return "run_output: (empty)";
		}
		if (rawOutput.length() <= MAX_OUTPUT_CHARS) {
			return "run_output:\n" + rawOutput;
		}
		int head = Math.min(RUN_HEAD_CHARS, rawOutput.length());
		int tail = Math.min(RUN_TAIL_CHARS, rawOutput.length() - head);
		int omitted = Math.max(0, rawOutput.length() - head - tail);
		StringBuilder output = new StringBuilder();
		output.append("run_output_head:\n");
		output.append(rawOutput, 0, head);
		output.append("\n... run output truncated, omitted ").append(omitted).append(" chars ...\n");
		if (tail > 0) {
			output.append("run_output_tail:\n");
			output.append(rawOutput.substring(rawOutput.length() - tail));
		}
		return output.toString();
	}

	private static void appendDebugLog(String runId, String hypothesisId, String location, String message,
			JSONObject data) {
		try {
			JSONObject payload = new JSONObject()
					.put("sessionId", "b8f46f")
					.put("runId", runId)
					.put("hypothesisId", hypothesisId)
					.put("location", location)
					.put("message", message)
					.put("data", data == null ? new JSONObject() : data)
					.put("timestamp", System.currentTimeMillis());
			Files.writeString(DEBUG_LOG_PATH, payload.toString() + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	private static void destroyProcessTree(Process process) {
		ProcessHandle handle = process.toHandle();
		handle.descendants().forEach(child -> {
			try {
				child.destroyForcibly();
			} catch (Exception ignored) {
			}
		});
		try {
			handle.destroyForcibly();
		} catch (Exception ignored) {
		}
	}

	private static class ManagedProcess {
		private final Process process;
		private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();

		private ManagedProcess(Process process) {
			this.process = process;
		}

		private void rememberDescendants() {
			process.toHandle().descendants().forEach(descendants::add);
		}

		private void destroy() {
			rememberDescendants();
			for (ProcessHandle descendant : descendants) {
				try {
					descendant.destroyForcibly();
				} catch (Exception ignored) {
				}
			}
			destroyProcessTree(process);
		}
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
