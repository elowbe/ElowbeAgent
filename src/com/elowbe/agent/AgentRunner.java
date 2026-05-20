package com.elowbe.agent;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.elowbe.tools.AgentTools;
import com.elowbe.tools.ToolChoiceSchema;
import com.elowbe.tools.ToolResult;

import lib.console.util.OllamaAPI;

public class AgentRunner {
	private static final int MAX_TURNS = 40;
	private static final int MAX_SUBTASK_DEPTH = 1;
	private static final int MAX_SUBTASK_RESULT_CHARS = 40_000;
	private static final BooleanSupplier NEVER_CANCEL = () -> false;
	private static final String SUBTASK_SYSTEM_PROMPT = """
			You are a focused worker agent for a larger coding task.
			Complete only the assigned subtask using the available tools.
			Do not broaden the task, do not ask for new work, and do not delegate subtasks.
			Return a concise result that includes what you changed or learned, any files touched,
			and anything the master agent must know before continuing.

			You must always respond with the JSON object required by the active Ollama JSON schema.
			Do not wrap it in markdown.

			Available tools:
			read: {"path":"relative/or/absolute/file"}
			bash: {"command":"shell command"}
			edit: {"path":"file","old":"exact text to replace","new":"replacement text"}
			write: {"path":"file","content":"new file contents"}
			done: {"response":"final result for the master agent"}

			You must call at least one non-done tool before done so the master agent has
			evidence that this worker actually ran. For code tasks, read relevant files,
			run an inspection command, edit files, write files, or run a verification command.
			If you cannot use a tool, call done with a clear explanation that no tool evidence
			was produced.
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

	private AgentRunner() {
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, NEVER_CANCEL);
	}

	public static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, BooleanSupplier cancelRequested) throws IOException {
		run(messages, workingDirectory, tokenSink, thinkingSink, toolSink, cancelRequested, 0);
	}

	public static String runSubtask(String task, String context, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException {
		return runSubtask(task, context, workingDirectory, cancelRequested, null, null);
	}

	private static String runSubtask(String task, String context, File workingDirectory, BooleanSupplier cancelRequested,
			ThinkingSink thinkingSink, ToolSink toolSink) throws IOException {
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
		run(messages, workingDirectory, result::append, thinkingSink, toolSink, cancelRequested, MAX_SUBTASK_DEPTH);
		String text = result.toString().trim();
		return text.isBlank() ? "Subtask finished without a final response." : text;
	}

	private static void run(JSONArray messages, File workingDirectory, TokenSink tokenSink,
			ThinkingSink thinkingSink, ToolSink toolSink, BooleanSupplier cancelRequested, int subtaskDepth)
			throws IOException {
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}
		JSONObject format = ToolChoiceSchema.build(subtaskDepth < MAX_SUBTASK_DEPTH);

		for (int turn = 0; turn < MAX_TURNS; turn++) {
			throwIfCancelled(cancelRequested);
			if (subtaskDepth > 0 && thinkingSink != null) {
				thinkingSink.accept("Subtask worker turn " + (turn + 1) + " started\n");
			}
			JSONObject assistantMessage = OllamaAPI.streamChatCompletion(messages, null, format,
					new OllamaAPI.ChatStreamListener() {
						@Override
						public void onToken(String token) {
						}

						@Override
						public void onThinking(String token) {
							if (thinkingSink != null) {
								thinkingSink.accept(token);
							}
						}
					}, null, cancelRequested);
			throwIfCancelled(cancelRequested);
			messages.put(assistantMessage);

			JSONObject step = parseStep(assistantMessage.optString("content", ""));
			if (step == null) {
				appendUserInstruction(messages,
						"Your last response did not match the required JSON schema. Return one valid JSON object only.");
				continue;
			}

			emitStepThinking(step, thinkingSink);

			JSONArray toolCalls = step.optJSONArray("tool_calls");
			boolean completedByTool = false;
			String toolFinalResponse = "";
			if (toolCalls != null && toolCalls.length() > 0) {
				for (int i = 0; i < toolCalls.length(); i++) {
					JSONObject call = toolCalls.optJSONObject(i);
					if (call == null) {
						appendToolResult(messages, "unknown", new JSONObject(), "Tool error: invalid tool call");
						continue;
					}

					String name = call.optString("name", "");
					JSONObject arguments = normalizeArguments(call.opt("arguments"));
					throwIfCancelled(cancelRequested);
					ToolResult result = executeTool(name, arguments, workingDirectory, cancelRequested, subtaskDepth,
							thinkingSink, toolSink);
					throwIfCancelled(cancelRequested);
					if (toolSink != null) {
						toolSink.accept(name, arguments, result.getOutput());
					}
					appendToolResult(messages, name, arguments, result.getOutput());

					if (result.isComplete()) {
						completedByTool = true;
						toolFinalResponse = result.getFinalResponse();
					}
				}
				if (completedByTool) {
					String response = toolFinalResponse.isBlank() ? step.optString("response", "") : toolFinalResponse;
					if (!response.isBlank() && tokenSink != null) {
						tokenSink.accept(response);
					}
					return;
				}
				continue;
			}

			if (step.optBoolean("complete", false) || "final".equals(step.optString("step"))) {
				String response = step.optString("response", "");
				if (!response.isBlank() && tokenSink != null) {
					tokenSink.accept(response);
				}
				return;
			}

			appendUserInstruction(messages,
					"No tool was called and the task is not complete. Continue with the next required step.");
		}

		if (tokenSink != null) {
			tokenSink.accept("Agent stopped after reaching the maximum tool loop count.");
		}
	}

	private static ToolResult executeTool(String name, JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested, int subtaskDepth, ThinkingSink thinkingSink, ToolSink toolSink)
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
			result = runSubtask(task, context, workingDirectory, cancelRequested,
					token -> {
						if (thinkingSink != null) {
							thinkingSink.accept(prefixSubtaskOutput(token));
						}
					},
					(toolName, toolArguments, toolResult) -> {
						if (!"done".equals(toolName)) {
							evidenceToolCount[0]++;
							appendEvidence(evidence, toolName, toolArguments, toolResult);
						}
						if (toolSink != null) {
							toolSink.accept("subtask." + toolName, toolArguments, toolResult);
						}
					});
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
		return "tool: " + name + "(" + (arguments == null ? "{}" : arguments.toString()) + ")";
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

	private static JSONObject normalizeArguments(Object value) {
		if (value instanceof JSONObject object) {
			return object;
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return new JSONObject(text);
			} catch (JSONException ignored) {
				return new JSONObject().put("value", text);
			}
		}
		return new JSONObject();
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

	private static void emitStepThinking(JSONObject step, ThinkingSink thinkingSink) {
		if (thinkingSink == null) {
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
}
