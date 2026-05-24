package com.elowbe.agent;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.elowbe.tools.AgentTools;
import com.elowbe.tools.ToolChoiceSchema;
import com.elowbe.tools.ToolResult;
import com.elowbe.tools.WebTool;
import com.elowbe.tools.maven.MavenTool;

import lib.console.util.OllamaAPI;

public class AgentRunner {
	private static final int MAX_TURNS = 400;
	private static final int MAX_SCHEMA_RETRIES = 3;
	private static final int MAX_SUBTASK_DEPTH = 1;
	private static final int MAX_SUBTASK_WORKER_TOOLS = 1;
	private static final int MAX_SUBTASK_RESULT_CHARS = 40_000;
	private static final BooleanSupplier NEVER_CANCEL = () -> false;
	private static final String RUN_SCRIPT_UNIX = """
			#!/usr/bin/env bash
			set -euo pipefail

			if [ -f "pom.xml" ]; then
			  mvn -q -DskipTests compile exec:java
			elif [ -f "build.gradle" ] || [ -f "build.gradle.kts" ]; then
			  ./gradlew run
			elif [ -f "package.json" ]; then
			  npm run start
			else
			  echo "No supported run target found (pom.xml, gradle build, or package.json)."
			  exit 1
			fi
			""";
	private static final String RUN_SCRIPT_WINDOWS = """
			@echo off
			setlocal

			if exist pom.xml (
			  call mvn -q -DskipTests compile exec:java
			  goto :eof
			)
			if exist build.gradle (
			  call gradlew.bat run
			  goto :eof
			)
			if exist build.gradle.kts (
			  call gradlew.bat run
			  goto :eof
			)
			if exist package.json (
			  call npm run start
			  goto :eof
			)

			echo No supported run target found (pom.xml, gradle build, or package.json).
			exit /b 1
			""";
	private static final String SUBTASK_SYSTEM_PROMPT = """
			You are a focused worker agent for a larger coding task.
			Perform exactly one assigned action, then terminate. Do not broaden the task,
			do not chain extra steps, do not ask for new work, and do not delegate subtasks.
			Return a concise result that includes what you changed or learned, any files touched,
			and anything the master agent must know before continuing.

			You must always respond with the JSON object required by the active JSON schema.
			Do not wrap it in markdown.

			Available tools (two-step: pick tool name in tool_call, then fill arguments):
			read: {"path":"relative/or/absolute/file"} — output uses LINE|content format
			bash: {"command":"shell command","timeout_seconds":120} — NOT for Maven, pom.xml, or browser/web tasks that the web tool can perform
			run: {"command":"optional override; defaults to run.sh/run.bat for this OS"}
			edit: {"path":"file","start_line":10,"end_line":12,"new":"replacement text"}
			write: {"path":"file","content":"new file contents"} — NOT for pom.xml
			maven: REQUIRED for all Maven/pom work. Actions: init, info, configure, compile, test, package, goal.
			  init: group_id, artifact_id required; version, java_version, name, description, packaging optional
			  info: no extra fields
			  configure: coordinates, properties, add_dependencies, remove_dependencies, add_plugins, remove_plugins
			  compile/test/package: quiet, skip_tests, args, timeout_seconds optional
			  goal: goals array or goal string required; quiet, skip_tests, args, timeout_seconds optional
			  Never use bash mvn when maven can do the job.
			web: {"action":"open|follow|search|api","url":"https://...","query":"search terms"} — configured browser tool
			  Prefer web over bash for web-based tasks.
			  open: load a page and return title, text excerpt, and links
			  follow: load url, then follow link_url, link_text, or link_index
			  search: query required; searches the web and returns results/links
			  api: url required; method, headers, body optional; tests HTTP APIs from a browser context
			done: {"response":"final result for the master agent"}

			HARD RULE — one action, then done:
			1) Call exactly one non-done tool that performs the assigned action.
			2) Call done with a summary of the tool result.
			Do not call a second non-done tool. Verification, follow-up edits, and integration
			are the master agent's job. If you cannot use a tool, call done with a clear explanation.
			""";

	@FunctionalInterface
	public interface TokenSink {
		void accept(String token);
	}

	@FunctionalInterface
	public interface ThinkingSink {
		void accept(String token);
	}

	@FunctionalInterface
	public interface ToolSink {
		void accept(String name, JSONObject arguments, String result);
	}

	@FunctionalInterface
	public interface UsageSink {
		void accept(JSONObject usage);
	}

	private AgentRunner() {
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, null, NEVER_CANCEL);
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, BooleanSupplier cancelRequested) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, null, cancelRequested);
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink, BooleanSupplier cancelRequested)
			throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, usageSink, cancelRequested, true, false);
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink, BooleanSupplier cancelRequested,
			boolean thinkingEnabled) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, usageSink, cancelRequested,
				thinkingEnabled, false);
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink, BooleanSupplier cancelRequested,
			boolean thinkingEnabled, boolean subtasksEnabled) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, usageSink, cancelRequested, 0,
				thinkingEnabled, subtasksEnabled);
	}

	public static String runSubtask(String task, String context, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException {
		return runSubtask(task, context, workingDirectory, cancelRequested, null, null, null);
	}

	private static String runSubtask(String task, String context, File workingDirectory, BooleanSupplier cancelRequested,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink) throws IOException {
		if (task == null || task.isBlank()) {
			return "Subtask error: missing task";
		}
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}
		JSONArray messages = new JSONArray();
		messages.put(new JSONObject()
				.put("role", "system")
				.put("content", SUBTASK_SYSTEM_PROMPT));
		StringBuilder userContent = new StringBuilder();
		userContent.append("Subtask:\n").append(task.trim());
		if (context != null && !context.isBlank()) {
			userContent.append("\n\nContext from master agent:\n").append(context.trim());
		}
		userContent.append("\n\nRun exactly one action tool for this assignment, then call done and stop.");
		messages.put(new JSONObject()
				.put("role", "user")
				.put("content", userContent.toString()));

		StringBuilder result = new StringBuilder();
		run(messages, workingDirectory, result::append, thinkingSink, toolSink, usageSink, cancelRequested,
				MAX_SUBTASK_DEPTH, OllamaAPI.thinkingEnabled, true);
		String text = result.toString().trim();
		return text.isBlank() ? "Subtask finished without a final response." : text;
	}

	private static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink, BooleanSupplier cancelRequested,
			int subtaskDepth, boolean thinkingEnabled, boolean subtasksEnabled)
			throws IOException {
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}
		if (subtaskDepth == 0) {
			ensureRunScripts(workingDirectory);
		}
		boolean includeSubtask = subtasksEnabled && subtaskDepth < MAX_SUBTASK_DEPTH;
		JSONObject format = ToolChoiceSchema.build(includeSubtask);
		CompletionGuard guard = new CompletionGuard();
		DelegationGuard delegationGuard = subtaskDepth == 0 && subtasksEnabled ? new DelegationGuard(messages) : null;
		SubtaskWorkerGuard subtaskWorkerGuard = subtaskDepth > 0 ? new SubtaskWorkerGuard() : null;
		StepLoopState loopState = new StepLoopState();
		int stepRetryStreak = 0;

		for (int turn = 0; turn < MAX_TURNS; turn++) {
			throwIfCancelled(cancelRequested);
			if (subtaskDepth > 0 && thinkingSink != null) {
				thinkingSink.accept("Subtask worker turn " + (turn + 1) + " started\n");
			}
			JSONObject assistantMessage = OllamaAPI.streamChatCompletion(messages, null, format,
					new OllamaAPI.ChatStreamListener() {
						@Override
						public void onToken(String token) {
							if (token == null || token.isEmpty()) {
								return;
							}
							if (subtaskDepth > 0) {
								if (thinkingSink != null) {
									thinkingSink.accept(prefixSubtaskOutput(token));
								}
							} else if (tokenSink != null) {
								tokenSink.accept(token);
							}
						}

						@Override
						public void onThinking(String token) {
							if (token == null || token.isEmpty() || thinkingSink == null) {
								return;
							}
							if (subtaskDepth > 0) {
								thinkingSink.accept(prefixSubtaskOutput(token));
							} else {
								thinkingSink.accept(token);
							}
						}

						@Override
						public void onUsage(JSONObject usage) {
							emitStreamingUsage(usage, usageSink);
						}
					}, null, cancelRequested);
			emitUsage(assistantMessage, usageSink);
			assistantMessage.remove("usage");
			throwIfCancelled(cancelRequested);

			String rawStepContent = extractAssistantText(assistantMessage, thinkingEnabled);
			JSONObject step = parseStep(rawStepContent);
			String stepShapeError = validateStepShape(step, rawStepContent, assistantMessage, thinkingEnabled);
			if (stepShapeError != null) {
				stepRetryStreak++;
				logSchemaRetry("agent step", stepRetryStreak, MAX_SCHEMA_RETRIES, stepShapeError, rawStepContent);
				if (stepRetryStreak >= MAX_SCHEMA_RETRIES) {
					throwSchemaRetryExhausted("agent step JSON", MAX_SCHEMA_RETRIES);
				}
				continue;
			}
			stepRetryStreak = 0;
			normalizeAssistantMessageContent(assistantMessage, thinkingEnabled);
			messages.put(assistantMessage);

			emitStepThinking(step, thinkingSink, loopState);

			JSONArray toolCalls = step.optJSONArray("tool_calls");
			boolean completedByTool = false;
			String toolFinalResponse = "";
			JSONObject call = extractToolCall(step);
			if (call != null) {
				loopState.onToolSelected();
				if (toolCalls != null && toolCalls.length() > 1) {
					appendUserInstruction(messages,
							"Only one tool is allowed per step. You sent "
									+ toolCalls.length()
									+ " tools; only the first was executed. Call one tool, wait for the result, then continue.");
				}

				String name = call.optString("name", "");
				if (!ToolChoiceSchema.isKnownTool(name, includeSubtask)) {
					appendUserInstruction(messages,
							"Unknown tool \"" + name + "\". Use tool_call.name with a supported tool only.");
					continue;
				}
				if (delegationGuard != null) {
					String delegationInstruction = delegationGuard.validateToolChoice(name, messages);
					if (delegationInstruction != null) {
						appendUserInstruction(messages, delegationInstruction);
						continue;
					}
				}
				if (subtaskWorkerGuard != null) {
					String workerInstruction = subtaskWorkerGuard.validateToolChoice(name);
					if (workerInstruction != null) {
						appendUserInstruction(messages, workerInstruction);
						continue;
					}
				}
				JSONObject arguments = resolveToolArguments(name, step, messages, tokenSink, thinkingSink, usageSink,
						cancelRequested, subtaskDepth, thinkingEnabled);
				if (arguments == null) {
					continue;
				}
				throwIfCancelled(cancelRequested);
				ToolResult result = executeTool(name, arguments, workingDirectory, cancelRequested, subtaskDepth,
						subtasksEnabled, thinkingSink, toolSink, usageSink);
				throwIfCancelled(cancelRequested);
				if (toolSink != null) {
					toolSink.accept(name, arguments, result.getOutput());
				}
				appendToolResult(messages, name, arguments, result.getOutput());
				guard.observe(name, arguments, result.getOutput());
				if (delegationGuard != null) {
					delegationGuard.observe(name, result.getOutput(), messages);
				}
				if (subtaskWorkerGuard != null) {
					subtaskWorkerGuard.observe(name);
					if (subtaskWorkerGuard.shouldFinishAfterTool(name)) {
						appendUserInstruction(messages, subtaskWorkerGuard.finishInstruction());
					}
				}
				loopState.onToolExecuted(result.isComplete());

				if (result.isComplete()) {
					completedByTool = true;
					toolFinalResponse = result.getFinalResponse();
				}
				if (completedByTool) {
					String response = pickFinalResponse(step, toolFinalResponse);
					if (subtaskDepth == 0) {
						response = guard.enrichFinalResponse(response);
					}
					if (!response.isBlank()) {
						emitFinalResponse(response, tokenSink, subtaskDepth);
					}
					return;
				}
				continue;
			}

			if (step.optBoolean("complete", false) || "final".equals(step.optString("step"))) {
				String response = pickFinalResponse(step, "");
				if (subtaskDepth == 0) {
					response = guard.enrichFinalResponse(response);
				}
				emitFinalResponse(response, tokenSink, subtaskDepth);
				return;
			}

			loopState.onPlanningWithoutTool(step);
			appendUserInstruction(messages, loopState.noToolInstruction(step));
		}

		if (tokenSink != null) {
			tokenSink.accept("Agent stopped after reaching the maximum tool loop count.");
		}
	}

	private static ToolResult executeTool(String name, JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested, int subtaskDepth, boolean subtasksEnabled, ThinkingSink thinkingSink,
			ToolSink toolSink, UsageSink usageSink)
			throws IOException {
		if (!"subtask".equals(name)) {
			return AgentTools.execute(name, arguments, workingDirectory, cancelRequested, subtaskDepth);
		}
		if (!subtasksEnabled) {
			return ToolResult.output("subtask: subtasks are disabled; finish this task directly with other tools");
		}
		if (subtaskDepth >= MAX_SUBTASK_DEPTH) {
			return ToolResult.output("subtask: nested subtasks are disabled; finish this worker task directly");
		}

		String task = first(arguments, "task", "instruction", "goal");
		if (task.isBlank()) {
			return ToolResult.output("subtask: missing task");
		}
		String context = first(arguments, "context", "summary");
		if (toolSink != null) {
			JSONObject displayedArguments = new JSONObject().put("task", task);
			if (!context.isBlank()) {
				displayedArguments.put("context", context);
			}
			toolSink.accept("subtask.start", displayedArguments, "START: worker context created");
		}
		if (thinkingSink != null) {
			thinkingSink.accept("Subtask worker starting: " + task + "\n");
		}

		int[] evidenceToolCount = { 0 };
		StringBuilder evidence = new StringBuilder();
		String result;
		try {
			result = runSubtask(task, context, workingDirectory, cancelRequested, thinkingSink,
					(toolName, toolArguments, toolResult) -> {
						if (!"done".equals(toolName)) {
							evidenceToolCount[0]++;
							appendEvidence(evidence, toolName, toolArguments, toolResult);
						}
						if (toolSink != null) {
							toolSink.accept("subtask." + toolName, toolArguments, toolResult);
						}
					}, usageSink);
		} catch (IOException e) {
			if (cancelRequested.getAsBoolean()) {
				throw e;
			}
			if (toolSink != null) {
				toolSink.accept("subtask.finish", new JSONObject().put("task", task),
						"FAILED: " + e.getMessage());
			}
			return ToolResult.output("subtask result:\n"
					+ "SUBTASK_STATUS: FAILED\n"
					+ "SUBTASK_ERROR: " + e.getMessage());
		}

		String status = evidenceToolCount[0] > 0 ? "VERIFIED_WITH_TOOL_ACTIVITY" : "FAILED_NO_TOOL_EVIDENCE";
		if (thinkingSink != null) {
			thinkingSink.accept("Subtask completed: " + status + " (" + evidenceToolCount[0] + " worker tools)\n");
		}
		if (toolSink != null) {
			toolSink.accept("subtask.finish", new JSONObject()
					.put("task", task)
					.put("status", status)
					.put("worker_tool_calls", evidenceToolCount[0]),
					"FINISH: " + status);
		}

		StringBuilder output = new StringBuilder();
		output.append("subtask result:\n");
		output.append("SUBTASK_STATUS: ").append(status).append('\n');
		output.append("SUBTASK_TOOL_CALLS: ").append(evidenceToolCount[0]).append('\n');
		if (evidenceToolCount[0] == 0) {
			output.append("SUBTASK_WARNING: worker returned no tool evidence; verify before relying on this result.\n");
		} else {
			output.append("SUBTASK_EVIDENCE:\n").append(evidence);
		}
		output.append("SUBTASK_FINAL_RESPONSE:\n").append(result);
		return ToolResult.output(truncate(output.toString(), MAX_SUBTASK_RESULT_CHARS));
	}

	public static String formatToolCall(String name, JSONObject arguments) {
		return formatToolAction(name, arguments);
	}

	/** Human-readable one-line description of a tool invocation (no raw JSON dumps). */
	public static String formatToolAction(String name, JSONObject arguments) {
		if (name == null) {
			name = "";
		}
		JSONObject args = arguments == null ? new JSONObject() : arguments;
		return switch (name) {
		case "read" -> "→ read " + shortPath(first(args, "path", "file"));
		case "write" -> {
			String path = shortPath(first(args, "path", "file"));
			int chars = first(args, "content", "text").length();
			yield "→ write " + path + " (" + chars + " chars)";
		}
		case "edit" -> {
			String path = shortPath(first(args, "path", "file"));
			int startLine = firstInt(args, 0, "start_line", "start");
			int endLine = firstInt(args, 0, "end_line", "end");
			int newLen = first(args, "new", "new_text", "replacement", "content").length();
			yield "→ edit " + path + " (lines " + startLine + "-" + endLine + ", " + newLen + " chars)";
		}
		case "bash" -> "→ bash: " + truncateInline(first(args, "command", "cmd"), 100);
		case "run" -> {
			String cmd = first(args, "command", "cmd");
			yield cmd.isBlank() ? "→ run (project script)" : "→ run: " + truncateInline(cmd, 100);
		}
		case "maven" -> MavenTool.describeAction(args);
		case "web" -> WebTool.describeAction(args);
		case "done" -> "→ done";
		case "subtask" -> "→ subtask: " + truncateInline(first(args, "task", "instruction", "goal"), 120);
		case "subtask.start" -> "── subagent: " + truncateInline(args.optString("task", "?"), 120) + " ──";
		case "subtask.finish" -> "── subagent done ──";
		default -> {
			if (name.startsWith("subtask.")) {
				yield "  subagent " + formatToolAction(name.substring("subtask.".length()), args);
			}
			yield "→ " + name;
		}
		};
	}

	/** Concise summary of a tool result for console logging (no file/command dumps). */
	public static String formatToolResultSummary(String name, String result) {
		if (name == null) {
			name = "";
		}
		if ("done".equals(name)) {
			return "";
		}
		if (result == null || result.isBlank()) {
			return "  (no output)";
		}
		if (name.startsWith("subtask.")) {
			return formatToolResultSummary(name.substring("subtask.".length()), result);
		}
		if ("subtask.finish".equals(name)) {
			return "  " + truncateInline(result, 120);
		}
		if (looksLikeToolError(name, result)) {
			return "  ✗ " + truncateInline(firstNonBlankLine(result), 160);
		}
		return switch (name) {
		case "read" -> formatReadSummary(result);
		case "write", "edit" -> "  ✓ " + truncateInline(firstNonBlankLine(result), 160);
		case "bash", "run" -> formatCommandOutputSummary(result);
		case "maven" -> MavenTool.describeResultSummary(result);
		case "web" -> WebTool.describeResultSummary(result);
		case "subtask" -> formatSubtaskSummary(result);
		default -> formatGenericSummary(result);
		};
	}

	private static boolean looksLikeToolError(String name, String result) {
		String line = firstNonBlankLine(result);
		if (line.startsWith("Tool error:")) {
			return true;
		}
		return switch (name) {
		case "read" -> line.startsWith("read:") && !line.startsWith("read: ");
		case "edit" -> line.startsWith("edit:") && !line.startsWith("edit: updated");
		case "write" -> line.startsWith("write:") && !line.startsWith("write: wrote");
		case "bash" -> line.startsWith("bash:") && !line.equals("bash: cancelled");
		case "maven" -> line.startsWith("maven:") || (line.startsWith("maven ") && line.contains("(exit ") && !line.contains("(exit 0)"));
		case "web" -> line.startsWith("web:") || line.startsWith("Tool error:");
		case "run" -> line.startsWith("run:") && line.contains("error");
		default -> false;
		};
	}

	private static String formatReadSummary(String result) {
		int lines = countLines(result);
		return "  ✓ " + lines + " lines, " + result.length() + " chars";
	}

	private static String formatCommandOutputSummary(String result) {
		String status;
		if (result.startsWith("Cancelled")) {
			status = "cancelled";
		} else if (result.startsWith("Timed out")) {
			status = "timed out";
		} else {
			status = null;
		}

		String stdout = extractSection(result, "stdout:\n", "stderr:\n");
		String stderr = extractSection(result, "stderr:\n", null);
		if (result.startsWith("run_command:")) {
			stdout = extractSection(result, "run_output:\n", null);
			if (stdout.isEmpty()) {
				stdout = extractSection(result, "run_output_head:\n", "run_output_tail:");
			}
			stderr = "";
		}

		StringBuilder summary = new StringBuilder("  ✓ ");
		if (status != null) {
			summary.append(status);
		} else {
			summary.append(countLines(stdout)).append(" stdout lines");
			if (!stderr.isBlank()) {
				summary.append(", ").append(countLines(stderr)).append(" stderr lines");
			}
		}

		String errLine = firstNonBlankLine(stderr);
		if (errLine != null) {
			summary.append("\n  ! ").append(truncateInline(errLine, 140));
		} else if (status == null && stdout.isBlank() && stderr.isBlank()) {
			String preview = firstNonBlankLine(result);
			if (preview != null && !preview.startsWith("run_command:")) {
				summary.append(": ").append(truncateInline(preview, 140));
			}
		}
		return summary.toString();
	}

	private static String formatSubtaskSummary(String result) {
		String status = extractMarkerLine(result, "SUBTASK_STATUS:");
		String toolCalls = extractMarkerLine(result, "SUBTASK_TOOL_CALLS:");
		StringBuilder summary = new StringBuilder("  ✓ subtask");
		if (!status.isBlank()) {
			summary.append(' ').append(status.toLowerCase(Locale.ROOT));
		}
		if (!toolCalls.isBlank()) {
			summary.append(" (").append(toolCalls).append(" tools)");
		}
		return summary.toString();
	}

	private static String formatGenericSummary(String result) {
		int lines = countLines(result);
		if (lines <= 1 && result.length() <= 160) {
			return "  ✓ " + result.trim();
		}
		return "  ✓ " + lines + " lines, " + result.length() + " chars";
	}

	private static String extractSection(String text, String startMarker, String endMarker) {
		int start = text.indexOf(startMarker);
		if (start < 0) {
			return "";
		}
		start += startMarker.length();
		int end = endMarker == null ? text.length() : text.indexOf(endMarker, start);
		if (end < 0) {
			end = text.length();
		}
		return text.substring(start, end).trim();
	}

	private static String extractMarkerLine(String text, String marker) {
		int idx = text.indexOf(marker);
		if (idx < 0) {
			return "";
		}
		int lineEnd = text.indexOf('\n', idx);
		String line = lineEnd < 0 ? text.substring(idx) : text.substring(idx, lineEnd);
		return line.substring(marker.length()).trim();
	}

	private static int countLines(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return text.split("\n", -1).length;
	}

	private static String truncateInline(String text, int maxChars) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
		if (oneLine.length() <= maxChars) {
			return oneLine;
		}
		return oneLine.substring(0, maxChars - 1) + "…";
	}

	private static String shortPath(String path) {
		if (path == null || path.isBlank()) {
			return "(no path)";
		}
		path = path.replace('\\', '/');
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		String[] parts = path.split("/");
		if (parts.length <= 2) {
			return path;
		}
		return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
	}

	private static JSONObject extractToolCall(JSONObject step) {
		if (step == null) {
			return null;
		}
		if (step.has("tool_call") && !step.isNull("tool_call")) {
			JSONObject call = step.optJSONObject("tool_call");
			if (call != null && !call.optString("name", "").isBlank()) {
				return call;
			}
		}
		JSONArray legacyCalls = step.optJSONArray("tool_calls");
		if (legacyCalls != null && legacyCalls.length() > 0) {
			JSONObject call = legacyCalls.optJSONObject(0);
			if (call != null && !call.optString("name", "").isBlank()) {
				return call;
			}
		}
		return null;
	}

	/**
	 * Second Ollama call: enforce tool-specific argument JSON schema after the step selects a tool name.
	 * The selection step's thought and plan are passed through so argument generation follows that intent.
	 */
	private static JSONObject resolveToolArguments(String toolName, JSONObject selectionStep, JSONArray messages,
			TokenSink tokenSink, ThinkingSink thinkingSink, UsageSink usageSink, BooleanSupplier cancelRequested,
			int subtaskDepth, boolean thinkingEnabled) throws IOException {
		JSONObject format = ToolChoiceSchema.buildToolArgumentsSchema(toolName);
		if (format == null) {
			return null;
		}

		int messageCountBeforeArguments = messages.length();
		appendUserInstruction(messages, toolArgumentPrompt(toolName, selectionStep));

		for (int attempt = 1; attempt <= MAX_SCHEMA_RETRIES; attempt++) {
			JSONObject assistantMessage = OllamaAPI.streamChatCompletion(messages, null, format,
					new OllamaAPI.ChatStreamListener() {
						@Override
						public void onToken(String token) {
							if (token == null || token.isEmpty()) {
								return;
							}
							if (subtaskDepth > 0) {
								if (thinkingSink != null) {
									thinkingSink.accept(prefixSubtaskOutput(token));
								}
							} else if (thinkingSink != null) {
								thinkingSink.accept(token);
							} else if (tokenSink != null) {
								tokenSink.accept(token);
							}
						}

						@Override
						public void onThinking(String token) {
							if (token == null || token.isEmpty() || thinkingSink == null) {
								return;
							}
							if (subtaskDepth > 0) {
								thinkingSink.accept(prefixSubtaskOutput(token));
							} else {
								thinkingSink.accept(token);
							}
						}

						@Override
						public void onUsage(JSONObject usage) {
							emitStreamingUsage(usage, usageSink);
						}
					}, null, cancelRequested);
			emitUsage(assistantMessage, usageSink);
			assistantMessage.remove("usage");
			throwIfCancelled(cancelRequested);

			String rawArgumentContent = extractAssistantText(assistantMessage, thinkingEnabled);
			JSONObject parsed = parseStep(rawArgumentContent);
			String argumentError = validateToolArgumentsShape(toolName, parsed, rawArgumentContent, assistantMessage,
					thinkingEnabled);
			if (argumentError != null) {
				logSchemaRetry("tool arguments for \"" + toolName + "\"", attempt, MAX_SCHEMA_RETRIES,
						argumentError, rawArgumentContent);
				if (attempt >= MAX_SCHEMA_RETRIES) {
					removeMessagesFrom(messages, messageCountBeforeArguments);
					return throwSchemaRetryExhausted("tool arguments for \"" + toolName + "\"", MAX_SCHEMA_RETRIES);
				}
				continue;
			}
			normalizeAssistantMessageContent(assistantMessage, thinkingEnabled);
			messages.put(assistantMessage);
			JSONObject wrapped = parsed.optJSONObject("arguments");
			if (wrapped != null) {
				return normalizeToolArguments(toolName, selectionStep, wrapped);
			}
			return normalizeToolArguments(toolName, selectionStep, parsed);
		}
		removeMessagesFrom(messages, messageCountBeforeArguments);
		return throwSchemaRetryExhausted("tool arguments for \"" + toolName + "\"", MAX_SCHEMA_RETRIES);
	}

	private static JSONObject normalizeToolArguments(String toolName, JSONObject selectionStep, JSONObject arguments) {
		if (!"maven".equals(toolName) || arguments == null || selectionStep == null) {
			return arguments;
		}
		String selectedIntent = (selectionStep.optString("thought", "") + "\n" + selectionStep.optString("plan", ""))
				.toLowerCase(Locale.ROOT);
		String generatedAction = arguments.optString("action", "").trim().toLowerCase(Locale.ROOT);
		String intendedAction = intendedMavenAction(selectedIntent);
		if (!intendedAction.isBlank() && ("info".equals(generatedAction) || generatedAction.isBlank())) {
			arguments.put("action", intendedAction);
		}
		return arguments;
	}

	private static String intendedMavenAction(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		for (String action : new String[] { "init", "configure", "compile", "test", "package", "goal" }) {
			if (text.contains("maven " + action) || text.contains("action " + action)
					|| text.contains("\"action\":\"" + action + "\"")
					|| text.contains("\"action\": \"" + action + "\"")) {
				return action;
			}
		}
		if (text.contains("javafx:run") || text.contains("spring-boot:run") || text.contains("exec:java")) {
			return "goal";
		}
		return "";
	}

	private static String toolArgumentPrompt(String toolName, JSONObject selectionStep) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("You selected the \"").append(toolName).append("\" tool in your previous step.\n");
		prompt.append("Your tool arguments MUST match the thought and plan from that step.\n");
		prompt.append("Do not ignore your previous thought/plan or repeat a prior failed tool call.\n\n");

		if (selectionStep != null) {
			String thought = selectionStep.optString("thought", "").trim();
			String plan = selectionStep.optString("plan", "").trim();
			if (!thought.isBlank()) {
				prompt.append("Thought from tool selection:\n").append(thought).append("\n\n");
			}
			if (!plan.isBlank()) {
				prompt.append("Plan from tool selection:\n").append(plan).append("\n\n");
			}
			String stepName = selectionStep.optString("step", "").trim();
			if (!stepName.isBlank()) {
				prompt.append("Selection step: ").append(stepName).append('\n');
			}
			JSONObject toolCall = selectionStep.optJSONObject("tool_call");
			if (toolCall != null && !toolCall.optString("name", "").isBlank()) {
				prompt.append("Selected tool: ").append(toolCall.optString("name")).append('\n');
			}
			prompt.append('\n');
		}

		if ("maven".equals(toolName)) {
			prompt.append(MavenTool.buildArgumentPrompt());
		} else {
			prompt.append("Return one JSON object with only that tool's arguments (schema enforced). ");
			prompt.append("Do not repeat the step object or tool_call wrapper.");
		}
		return prompt.toString().trim();
	}

	private static JSONObject parseStep(String content) {
		if (content == null) {
			return null;
		}
		String text = content.trim();
		if (text.startsWith("```")) {
			text = stripFence(text);
		}

		try {
			return new JSONObject(text);
		} catch (JSONException e) {
			int start = text.indexOf('{');
			int end = text.lastIndexOf('}');
			if (start >= 0 && end > start) {
				try {
					return new JSONObject(text.substring(start, end + 1));
				} catch (JSONException ignored) {
					return null;
				}
			}
			return null;
		}
	}

	private static void logSchemaRetry(String context, int attempt, int maxAttempts, String reason, String rawContent) {
		String preview = truncateInline(rawContent == null ? "" : rawContent, 160);
		System.out.println("[ElowbeAgent] Schema retry " + attempt + "/" + maxAttempts + " (" + context + "): "
				+ reason + (preview.isBlank() ? "" : " | response: " + preview));
	}

	private static JSONObject throwSchemaRetryExhausted(String context, int maxAttempts) throws IOException {
		throw new IOException("Agent error: LLM failed to return valid JSON for " + context + " after "
				+ maxAttempts + " attempts");
	}

	private static void normalizeAssistantMessageContent(JSONObject assistantMessage, boolean thinkingEnabled) {
		if (assistantMessage == null || !thinkingEnabled) {
			return;
		}
		if (!assistantMessage.optString("content", "").isBlank()) {
			return;
		}
		String thinking = assistantMessage.optString("thinking", "").trim();
		if (!thinking.isBlank()) {
			assistantMessage.put("content", thinking);
		}
	}

	private static String extractAssistantText(JSONObject assistantMessage, boolean thinkingEnabled) {
		if (assistantMessage == null) {
			return "";
		}
		String content = assistantMessage.optString("content", "").trim();
		if (!content.isBlank()) {
			return content;
		}
		if (!thinkingEnabled) {
			return "";
		}
		String thinking = assistantMessage.optString("thinking", "").trim();
		if (!thinking.isBlank()) {
			return thinking;
		}
		return "";
	}

	private static String describeAssistantMessageFields(JSONObject assistantMessage) {
		if (assistantMessage == null) {
			return "no assistant message";
		}
		int contentChars = assistantMessage.optString("content", "").length();
		int thinkingChars = assistantMessage.optString("thinking", "").length();
		return "content=" + contentChars + " chars, thinking=" + thinkingChars + " chars";
	}

	private static String validateStepShape(JSONObject step, String rawContent, JSONObject assistantMessage,
			boolean thinkingEnabled) {
		if (step == null) {
			return describeJsonParseFailure(rawContent, assistantMessage, thinkingEnabled);
		}

		StringBuilder missing = new StringBuilder();
		for (String field : new String[] { "step", "thought", "plan", "tool_call", "complete", "response" }) {
			if (!step.has(field)) {
				if (missing.length() > 0) {
					missing.append(", ");
				}
				missing.append(field);
			}
		}
		if (missing.length() > 0) {
			return "missing required field(s): " + missing;
		}

		String stepName = step.optString("step", "");
		if (!"think_and_plan".equals(stepName) && !"execute_tool".equals(stepName)
				&& !"confirm_tool_results".equals(stepName) && !"check_complete".equals(stepName)
				&& !"final".equals(stepName)) {
			if (stepName.isBlank()) {
				return "step is empty; expected one of think_and_plan, execute_tool, confirm_tool_results, check_complete, final";
			}
			return "invalid step value \"" + stepName
					+ "\"; expected one of think_and_plan, execute_tool, confirm_tool_results, check_complete, final";
		}

		if (!step.isNull("tool_call") && step.optJSONObject("tool_call") == null) {
			return "tool_call must be null or a JSON object, got " + jsonValueType(step.get("tool_call"));
		}

		return null;
	}

	private static String validateToolArgumentsShape(String toolName, JSONObject parsed, String rawContent,
			JSONObject assistantMessage, boolean thinkingEnabled) {
		if (parsed == null) {
			return describeJsonParseFailure(rawContent, assistantMessage, thinkingEnabled);
		}
		if (parsed.has("step") || parsed.has("tool_call")) {
			return "returned a full agent step object; expected tool arguments only for \"" + toolName + "\"";
		}
		if (parsed.length() == 0) {
			return "empty JSON object; expected tool arguments for \"" + toolName + "\"";
		}
		return null;
	}

	private static String describeJsonParseFailure(String rawContent, JSONObject assistantMessage,
			boolean thinkingEnabled) {
		if (rawContent == null || rawContent.isBlank()) {
			if (assistantMessage != null) {
				int contentChars = assistantMessage.optString("content", "").length();
				int thinkingChars = assistantMessage.optString("thinking", "").length();
				if (contentChars == 0 && thinkingChars > 0) {
					if (!thinkingEnabled) {
						return "content field empty and thinking is disabled (model sent " + thinkingChars
								+ " thinking chars)";
					}
					return "content field empty but thinking has " + thinkingChars
							+ " chars; model may have put JSON only in the thinking channel";
				}
			}
			return "empty response (" + describeAssistantMessageFields(assistantMessage) + ")";
		}
		String text = rawContent.trim();
		if (text.startsWith("```")) {
			return "response wrapped in markdown code fence; return raw JSON only";
		}
		if (!text.contains("{")) {
			return "response is not a JSON object (no '{' found)";
		}

		String parseError = tryParseJsonObject(text);
		if (parseError == null) {
			return "could not parse JSON object from response";
		}
		return parseError;
	}

	private static String tryParseJsonObject(String text) {
		try {
			new JSONObject(text);
			return null;
		} catch (JSONException directError) {
			int start = text.indexOf('{');
			int end = text.lastIndexOf('}');
			if (start >= 0 && end > start) {
				try {
					new JSONObject(text.substring(start, end + 1));
					return null;
				} catch (JSONException extractedError) {
					return "invalid JSON: " + extractedError.getMessage();
				}
			}
			return "invalid JSON: " + directError.getMessage();
		}
	}

	private static String jsonValueType(Object value) {
		if (value == null || value == JSONObject.NULL) {
			return "null";
		}
		if (value instanceof JSONObject) {
			return "object";
		}
		if (value instanceof JSONArray) {
			return "array";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		if (value instanceof Number) {
			return "number";
		}
		return "string";
	}

	private static String stripFence(String text) {
		int firstNewline = text.indexOf('\n');
		if (firstNewline >= 0) {
			text = text.substring(firstNewline + 1);
		}
		if (text.endsWith("```")) {
			text = text.substring(0, text.length() - 3);
		}
		return text.trim();
	}

	private static String first(JSONObject object, String... keys) {
		if (object == null) {
			return "";
		}
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key));
			}
		}
		return "";
	}

	private static int firstInt(JSONObject object, int defaultValue, String... keys) {
		if (object == null) {
			return defaultValue;
		}
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

	private static String prefixSubtaskOutput(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return text;
	}

	private static void appendEvidence(StringBuilder evidence, String toolName, JSONObject arguments, String result) {
		evidence.append("- ").append(toolName).append("(")
				.append(arguments == null ? "{}" : arguments.toString())
				.append(") -> ")
				.append(firstNonBlankLine(result))
				.append('\n');
	}

	private static String firstNonBlankLine(String text) {
		if (text == null || text.isBlank()) {
			return "(no output)";
		}
		for (String line : text.split("\n")) {
			line = line.trim();
			if (!line.isBlank()) {
				return truncate(line, 500);
			}
		}
		return "(blank output)";
	}

	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) {
			return text == null ? "" : text;
		}
		return text.substring(0, maxChars) + "\n... truncated ...";
	}

	private static void emitStepThinking(JSONObject step, ThinkingSink thinkingSink, StepLoopState loopState) {
		if (thinkingSink == null || loopState == null) {
			return;
		}
		if (loopState.shouldSuppressThinking(step)) {
			return;
		}
		String thought = step.optString("thought", "");
		String plan = step.optString("plan", "");
		if (!thought.isBlank()) {
			thinkingSink.accept(thought + "\n");
		}
		if (!plan.isBlank()) {
			thinkingSink.accept(plan + "\n");
		}
	}

	private static String thoughtPlanFingerprint(JSONObject step) {
		if (step == null) {
			return "";
		}
		return (step.optString("thought", "") + "|" + step.optString("plan", "")).trim().toLowerCase(Locale.ROOT);
	}

	private static boolean isSimilarThoughtPlan(String current, String previous) {
		if (current.isEmpty() || previous.isEmpty()) {
			return false;
		}
		if (current.equals(previous)) {
			return true;
		}
		int prefixLen = Math.min(60, Math.min(current.length(), previous.length()));
		if (prefixLen < 20) {
			return false;
		}
		String currentPrefix = current.substring(0, prefixLen);
		return previous.contains(currentPrefix) || current.contains(previous.substring(0, prefixLen));
	}

	private static final class StepLoopState {
		private boolean afterToolResult;
		private int planningStreak;
		private String lastThoughtPlan = "";

		private void onToolSelected() {
			planningStreak = 0;
		}

		private void onToolExecuted(boolean done) {
			afterToolResult = !done;
			planningStreak = 0;
			lastThoughtPlan = "";
		}

		private void onPlanningWithoutTool(JSONObject step) {
			String current = thoughtPlanFingerprint(step);
			if (planningStreak > 0 && isSimilarThoughtPlan(current, lastThoughtPlan)) {
				planningStreak += 2;
			} else {
				planningStreak++;
			}
			lastThoughtPlan = current;
		}

		private boolean shouldSuppressThinking(JSONObject step) {
			return planningStreak > 0
					&& isSimilarThoughtPlan(thoughtPlanFingerprint(step), lastThoughtPlan);
		}

		private String noToolInstruction(JSONObject step) {
			String stepName = step == null ? "" : step.optString("step", "");
			if (afterToolResult) {
				return """
						The previous turn returned a tool result as a user message starting with Tool "..." finished.
						Read that result carefully before acting.
						If the tool failed, fix the cause or try a different approach — do not repeat the same failing tool call.
						Your next JSON must either:
						- use step=execute_tool with tool_call.name set to the next tool, or
						- call done if the task is finished.
						Do not use think_and_plan, confirm_tool_results, or check_complete without a tool.
						""";
			}
			if ("execute_tool".equals(stepName)) {
				return "step is execute_tool but tool_call.name is missing. Set tool_call.name to the tool you intend to run.";
			}
			if (planningStreak >= 2) {
				return """
						You have repeated the same plan without running a tool.
						Stop planning. Your next JSON must use step=execute_tool with tool_call.name set.
						Put any brief reasoning in thought, then choose the tool in the same response.
						""";
			}
			return """
					You decided what to do but did not call a tool.
					Use step=execute_tool and set tool_call.name in this same response.
					Do not use think_and_plan again until after the tool runs.
					""";
		}
	}

	private static void appendToolResult(JSONArray messages, String name, JSONObject arguments, String output) {
		String resultText = output == null ? "" : output;
		String argumentText = arguments == null ? "{}" : arguments.toString(2);
		String content = """
				Tool "%s" finished. Read this result before choosing your next action.
				If the tool failed or returned an error, do not repeat the same call without fixing the cause.

				Arguments:
				%s

				Result:
				%s
				""".formatted(name, argumentText, resultText);
		messages.put(new JSONObject()
				.put("role", "user")
				.put("content", content.trim()));
	}

	private static void emitFinalResponse(String response, TokenSink tokenSink, int subtaskDepth) {
		if (response.isBlank() || tokenSink == null) {
			return;
		}
		tokenSink.accept(subtaskDepth > 0 ? response : "\n" + response);
	}

	private static void emitStreamingUsage(JSONObject usage, UsageSink usageSink) {
		if (usageSink == null || usage == null || usage.length() == 0) {
			return;
		}
		JSONObject copy = new JSONObject(usage.toString());
		copy.put("__streaming", true);
		usageSink.accept(copy);
	}

	private static void emitUsage(JSONObject assistantMessage, UsageSink usageSink) {
		if (usageSink == null || assistantMessage == null) {
			return;
		}
		JSONObject usage = assistantMessage.optJSONObject("usage");
		if (usage != null && usage.length() > 0) {
			JSONObject copy = new JSONObject(usage.toString());
			copy.put("__streaming", false);
			usageSink.accept(copy);
		}
	}

	private static String pickFinalResponse(JSONObject step, String toolFinalResponse) {
		String stepResponse = step == null ? "" : step.optString("response", "").trim();
		String toolResponse = toolFinalResponse == null ? "" : toolFinalResponse.trim();
		if (stepResponse.isEmpty()) {
			return toolResponse;
		}
		if (toolResponse.isEmpty()) {
			return stepResponse;
		}
		if (stepResponse.contains(toolResponse) || toolResponse.contains(stepResponse)) {
			return stepResponse.length() >= toolResponse.length() ? stepResponse : toolResponse;
		}
		return toolResponse + "\n\n" + stepResponse;
	}

	private static void appendUserInstruction(JSONArray messages, String content) {
		messages.put(new JSONObject()
				.put("role", "user")
				.put("content", content));
	}

	private static void removeMessagesFrom(JSONArray messages, int startIndex) {
		if (messages == null) {
			return;
		}
		for (int i = messages.length() - 1; i >= startIndex; i--) {
			messages.remove(i);
		}
	}

	private static void throwIfCancelled(BooleanSupplier cancelRequested) throws InterruptedIOException {
		if (cancelRequested.getAsBoolean()) {
			throw new InterruptedIOException("Agent cancelled");
		}
	}

	private static void ensureRunScripts(File workingDirectory) {
		if (workingDirectory == null) {
			return;
		}
		try {
			if (!workingDirectory.exists()) {
				workingDirectory.mkdirs();
			}
			Path runSh = new File(workingDirectory, "run.sh").toPath();
			Path runBat = new File(workingDirectory, "run.bat").toPath();
			if (!Files.exists(runSh)) {
				Files.writeString(runSh, RUN_SCRIPT_UNIX, StandardCharsets.UTF_8);
			}
			if (!Files.exists(runBat)) {
				Files.writeString(runBat, RUN_SCRIPT_WINDOWS, StandardCharsets.UTF_8);
			}
			runSh.toFile().setExecutable(true, false);
		} catch (IOException ignored) {
			// Non-fatal: run scripts are a convenience for manual or requested verification.
		}
	}

	private static class CompletionGuard {
		private static final int RUN_ANALYSIS_APPEND_CHARS = 6_000;
		private boolean runScriptExecuted;
		private String lastRunOutput = "";

		private void observe(String name, JSONObject arguments, String output) {
			if ("run".equals(name)) {
				runScriptExecuted = true;
				lastRunOutput = output == null ? "" : output;
				return;
			}
			if (!"bash".equals(name)) {
				return;
			}
			String command = first(arguments, "command", "cmd").toLowerCase(Locale.ROOT);
			if (command.contains("run.sh") || command.contains("run.bat")) {
				runScriptExecuted = true;
				lastRunOutput = output == null ? "" : output;
			}
		}

		private String enrichFinalResponse(String response) {
			String text = response == null ? "" : response.trim();
			if (!runScriptExecuted || lastRunOutput.isBlank()) {
				return text;
			}
			if (mentionsRunOutput(text)) {
				return text;
			}
			String summary = summarizeRunOutput(lastRunOutput);
			if (summary.isBlank()) {
				return text;
			}
			if (text.isEmpty()) {
				return "Run output analysis:\n" + summary;
			}
			return text + "\n\nRun output analysis:\n" + summary;
		}

		private static boolean mentionsRunOutput(String text) {
			if (text == null || text.isBlank()) {
				return false;
			}
			String lower = text.toLowerCase(Locale.ROOT);
			if (lower.contains("run output analysis") || lower.contains("run output:")) {
				return true;
			}
			if (lower.contains("stdout") || lower.contains("stderr")) {
				return true;
			}
			if (lower.contains("standard output") || lower.contains("standard error")) {
				return true;
			}
			return lower.contains("analysis") && (lower.contains("run") || lower.contains("output")
					|| lower.contains("log") || lower.contains("finding"));
		}

		private static String summarizeRunOutput(String output) {
			if (output == null || output.isBlank()) {
				return "";
			}
			String trimmed = output.trim();
			if (trimmed.length() <= RUN_ANALYSIS_APPEND_CHARS) {
				return trimmed;
			}
			return trimmed.substring(trimmed.length() - RUN_ANALYSIS_APPEND_CHARS);
		}
	}

	private static class SubtaskWorkerGuard {
		private int actionToolCount;

		private String validateToolChoice(String name) {
			if (actionToolCount < MAX_SUBTASK_WORKER_TOOLS || "done".equals(name)) {
				return null;
			}
			return """
					SUBTASK LIMIT:
					Workers may run exactly one action tool, then must terminate with done.
					You already used your single tool. Call done now with a concise summary for the master agent.
					""";
		}

		private void observe(String name) {
			if (name != null && !"done".equals(name)) {
				actionToolCount++;
			}
		}

		private boolean shouldFinishAfterTool(String name) {
			return !"done".equals(name) && actionToolCount >= MAX_SUBTASK_WORKER_TOOLS;
		}

		private String finishInstruction() {
			return """
					Your single action tool has finished.
					Do not call read, bash, run, edit, write, or maven again.
					Your next and only step is done with a concise summary of the tool result.
					""";
		}
	}

	private static class DelegationGuard {
		private static final int FORCE_AFTER_DIRECT_TOOLS = 5;
		private static final int FORCE_AFTER_RESULT_CHARS = 45_000;
		private static final int FORCE_AFTER_MESSAGE_CHARS = 90_000;
		private int messageBaselineChars;
		private int directToolCalls;
		private int directResultChars;
		private int warnings;

		private DelegationGuard(JSONArray startingMessages) {
			messageBaselineChars = messageChars(startingMessages);
		}

		private String validateToolChoice(String name, JSONArray messages) {
			if (name == null || name.isBlank() || "subtask".equals(name) || "done".equals(name)
					|| "run".equals(name)) {
				return null;
			}
			int activeMessageChars = Math.max(0, messageChars(messages) - messageBaselineChars);
			boolean directToolLimitReached = directToolCalls >= FORCE_AFTER_DIRECT_TOOLS;
			boolean resultLimitReached = directResultChars >= FORCE_AFTER_RESULT_CHARS;
			boolean messageLimitReached = activeMessageChars >= FORCE_AFTER_MESSAGE_CHARS;
			if (!directToolLimitReached && !resultLimitReached && !messageLimitReached) {
				return null;
			}
			warnings++;
			return """
					CONTEXT BUDGET WARNING:
					The master agent has continued with direct tools without delegating.
					Context added since the last worker roughly %d chars; direct tool output roughly %d chars across %d direct tool call(s).

					Do not run "%s" now. Your next JSON must use step=execute_tool with tool_call.name="subtask".
					Delegate one narrow, self-contained unit of work to a worker agent and include enough context for that worker to finish.
					After the subtask returns, inspect SUBTASK_STATUS, SUBTASK_TOOL_CALLS, and SUBTASK_EVIDENCE before continuing.
					This is delegation warning #%d; continuing without subtask risks exhausting the main context.
					""".formatted(activeMessageChars, directResultChars, directToolCalls, name, warnings);
		}

		private void observe(String name, String output, JSONArray messages) {
			if ("subtask".equals(name)) {
				directToolCalls = 0;
				directResultChars = 0;
				messageBaselineChars = messageChars(messages);
				return;
			}
			if ("done".equals(name) || "run".equals(name)) {
				return;
			}
			directToolCalls++;
			if (output != null) {
				directResultChars += output.length();
			}
		}

		private static int messageChars(JSONArray messages) {
			if (messages == null) {
				return 0;
			}
			int chars = 0;
			for (int i = 0; i < messages.length(); i++) {
				Object value = messages.opt(i);
				if (value != null) {
					chars += value.toString().length();
				}
			}
			return chars;
		}
	}
}
