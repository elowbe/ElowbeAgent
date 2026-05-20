package com.elowbe.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;

import com.elowbe.agent.AgentRunner;

public class AgentTools {
	private static final int MAX_OUTPUT_CHARS = 40_000;
	private static final Duration BASH_TIMEOUT = Duration.ofSeconds(60);
	private static final BooleanSupplier NEVER_CANCEL = () -> false;
	private static final Set<ManagedProcess> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();
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
			case "edit" -> edit(arguments, workingDirectory);
			case "write" -> write(arguments, workingDirectory);
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
		return ToolResult.output(truncate(content));
	}

	private static ToolResult bash(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException, InterruptedException {
		String command = first(arguments, "command", "cmd");
		if (command.isBlank()) {
			return ToolResult.output("bash: missing command");
		}
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
		long deadline = System.nanoTime() + BASH_TIMEOUT.toNanos();
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
				if (System.nanoTime() >= deadline) {
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
		} else if (timedOut) {
			result.append("Timed out after ").append(BASH_TIMEOUT.toSeconds())
					.append(" seconds while the process was still alive\n");
		}
		result.append("exit_code: ");
		if (completed) {
			result.append(process.exitValue());
		} else if (cancelled) {
			result.append("cancelled");
		} else {
			result.append("timeout");
		}
		result.append('\n');
		if (!stdout.text().isBlank()) {
			result.append("stdout:\n").append(stdout.text()).append('\n');
		}
		if (!stderr.text().isBlank()) {
			result.append("stderr:\n").append(stderr.text()).append('\n');
		}
		return ToolResult.output(truncate(result.toString().trim()));
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
