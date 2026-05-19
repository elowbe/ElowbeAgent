package com.elowbe.agent;

import java.io.File;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.elowbe.tools.AgentTools;
import com.elowbe.tools.ToolChoiceSchema;
import com.elowbe.tools.ToolResult;

import lib.console.util.OllamaAPI;

public class AgentRunner {
	private static final int MAX_TURNS = 40;

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
		JSONObject format = ToolChoiceSchema.build();

		for (int turn = 0; turn < MAX_TURNS; turn++) {
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
					}, null);
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
					ToolResult result = AgentTools.execute(name, arguments, workingDirectory);
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
}
