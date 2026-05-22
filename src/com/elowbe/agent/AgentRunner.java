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

import lib.console.util.OllamaAPI;

public class AgentRunner {
	private static final int MAX_TURNS = 400;
	private static final int MAX_SUBTASK_DEPTH = 1;
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
			Complete only the assigned subtask using the available tools.
			Do not broaden the task, do not ask for new work, and do not delegate subtasks.
			Return a concise result that includes what you changed or learned, any files touched,
			and anything the master agent must know before continuing.

			You must always respond with the JSON object required by the active JSON schema.
			Do not wrap it in markdown.

			Available tools (two-step: pick tool name in tool_call, then fill arguments):
			read: {"path":"relative/or/absolute/file"}
			bash: {"command":"shell command","timeout_seconds":120}
			run: {"command":"optional override; defaults to run.sh/run.bat for this OS"}
			edit: {"path":"file","old":"exact text to replace","new":"replacement text"}
			write: {"path":"file","content":"new file contents"}
			done: {"response":"final result for the master agent"}

			You must call exactly one tool per step. When you know the tool, use
			step=execute_tool with tool_call.name in the same response—do not replan first.
			After each tool result, the next turn must set tool_call.name (next tool or done).
			Arguments are collected automatically in a follow-up request with a tool-specific schema.
			You must call at least one non-done tool before done so the master agent has
			evidence that this worker actually ran. For code tasks, read relevant files,
			run an inspection command, edit files, write files, or run a verification command.
			If you cannot use a tool, call done with a clear explanation that no tool evidence
			was produced.

			HARD RULE:
			Before calling done, run the project via the generated run script in the current directory:
			- preferred: run tool with no arguments
			- fallback: macOS/Linux ./run.sh (or bash run.sh), Windows cmd /c run.bat
			Then include a run output analysis in your final response with:
			- key stdout/stderr findings
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
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, usageSink, cancelRequested, 0);
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
		userContent.append("\n\nDo this one thing and finish with the done tool.");
		messages.put(new JSONObject()
				.put("role", "user")
				.put("content", userContent.toString()));

		StringBuilder result = new StringBuilder();
		run(messages, workingDirectory, result::append, thinkingSink, toolSink, usageSink, cancelRequested,
				MAX_SUBTASK_DEPTH);
		String text = result.toString().trim();
		return text.isBlank() ? "Subtask finished without a final response." : text;
	}

	private static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, UsageSink usageSink, BooleanSupplier cancelRequested,
			int subtaskDepth)
			throws IOException {
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}
		if (subtaskDepth == 0) {
			ensureRunScripts(workingDirectory);
		}
		JSONObject format = ToolChoiceSchema.build(subtaskDepth < MAX_SUBTASK_DEPTH);
		CompletionGuard guard = new CompletionGuard();
		StepLoopState loopState = new StepLoopState();

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
			messages.put(assistantMessage);

			JSONObject step = parseStep(assistantMessage.optString("content", ""));
			if (step == null) {
				appendUserInstruction(messages,
						"Your last response did not match the required JSON schema. Return one valid JSON object only.");
				continue;
			}

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
				boolean includeSubtask = subtaskDepth < MAX_SUBTASK_DEPTH;
				if (!ToolChoiceSchema.isKnownTool(name, includeSubtask)) {
					appendUserInstruction(messages,
							"Unknown tool \"" + name + "\". Use tool_call.name with a supported tool only.");
					continue;
				}
				JSONObject arguments = resolveToolArguments(name, messages, tokenSink, thinkingSink, usageSink,
						cancelRequested, subtaskDepth);
				if (arguments == null) {
					appendUserInstruction(messages,
							"Could not obtain valid arguments for tool \"" + name
									+ "\". Try the same tool again.");
					continue;
				}
				throwIfCancelled(cancelRequested);
				ToolResult result = executeTool(name, arguments, workingDirectory, cancelRequested, subtaskDepth,
						thinkingSink, toolSink, usageSink);
				throwIfCancelled(cancelRequested);
				if (toolSink != null) {
					toolSink.accept(name, arguments, result.getOutput());
				}
				appendToolResult(messages, name, arguments, result.getOutput());
				guard.observe(name, arguments, result.getOutput());
				loopState.onToolExecuted(result.isComplete());

				if (result.isComplete()) {
					completedByTool = true;
					toolFinalResponse = result.getFinalResponse();
				}
				if (completedByTool) {
					String response = pickFinalResponse(step, toolFinalResponse);
					if (subtaskDepth == 0) {
						String validationError = guard.validateFinalResponse(response);
						if (validationError != null) {
							appendUserInstruction(messages, validationError);
							continue;
						}
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
					String validationError = guard.validateFinalResponse(response);
					if (validationError != null) {
						appendUserInstruction(messages, validationError);
						continue;
					}
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
			BooleanSupplier cancelRequested, int subtaskDepth, ThinkingSink thinkingSink, ToolSink toolSink,
			UsageSink usageSink)
			throws IOException {
		if (!"subtask".equals(name)) {
			return AgentTools.execute(name, arguments, workingDirectory, cancelRequested, subtaskDepth);
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
			int oldLen = first(args, "old", "old_text", "target").length();
			int newLen = first(args, "new", "new_text", "replacement").length();
			yield "→ edit " + path + " (" + oldLen + " → " + newLen + " chars)";
		}
		case "bash" -> "→ bash: " + truncateInline(first(args, "command", "cmd"), 100);
		case "run" -> {
			String cmd = first(args, "command", "cmd");
			yield cmd.isBlank() ? "→ run (project script)" : "→ run: " + truncateInline(cmd, 100);
		}
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
	 */
	private static JSONObject resolveToolArguments(String toolName, JSONArray messages, TokenSink tokenSink,
			ThinkingSink thinkingSink, UsageSink usageSink, BooleanSupplier cancelRequested, int subtaskDepth)
			throws IOException {
		JSONObject format = ToolChoiceSchema.buildToolArgumentsSchema(toolName);
		if (format == null) {
			return null;
		}

		appendUserInstruction(messages,
				"You selected the \"" + toolName + "\" tool. Return one JSON object with only that tool's "
						+ "arguments (schema enforced). Do not repeat the step object or tool_call wrapper.");

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
		messages.put(assistantMessage);

		JSONObject parsed = parseStep(assistantMessage.optString("content", ""));
		if (parsed == null) {
			return null;
		}
		JSONObject wrapped = parsed.optJSONObject("arguments");
		if (wrapped != null) {
			return wrapped;
		}
		return parsed;
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

	private static String prefixSubtaskOutput(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return "[subtask] " + text.replace("\n", "\n[subtask] ");
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
						The previous turn returned a tool result. Do not replan or restate the same decision.
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
		JSONObject message = new JSONObject();
		message.put("role", "tool");
		message.put("name", name);
		message.put("content", new JSONObject()
				.put("tool", name)
				.put("arguments", arguments == null ? new JSONObject() : arguments)
				.put("result", output == null ? "" : output)
				.toString());
		messages.put(message);
	}

	private static void emitFinalResponse(String response, TokenSink tokenSink, int subtaskDepth) {
		if (response.isBlank() || tokenSink == null) {
			return;
		}
		// Main agent output is already streamed token-by-token; subtasks collect a summary.
		if (subtaskDepth > 0) {
			tokenSink.accept(response);
		}
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
			// Non-fatal: completion guard still requires script execution evidence.
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

		private String validateFinalResponse(String response) {
			if (runScriptExecuted) {
				return null;
			}
			return """
					HARD RULE NOT SATISFIED:
					Do not finish yet.
					1) Ensure run.sh and run.bat exist in the current directory.
					2) Run the program via script:
					   - preferred: run tool with no arguments
					   - fallback: macOS/Linux ./run.sh (or bash run.sh), Windows cmd /c run.bat
					3) Then call done again.
					""";
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
}
