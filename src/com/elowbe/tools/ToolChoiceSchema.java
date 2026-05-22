package com.elowbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public class ToolChoiceSchema {
	private ToolChoiceSchema() {
	}

	public static JSONObject build() {
		return build(true);
	}

	/** Schema for the agent step (tool name only; arguments are filled in a second call). */
	public static JSONObject build(boolean includeSubtask) {
		JSONObject schema = object();
		schema.put("additionalProperties", false);
		schema.put("required", new JSONArray()
				.put("step")
				.put("thought")
				.put("plan")
				.put("complete")
				.put("response"));

		JSONObject properties = new JSONObject();
		properties.put("step", new JSONObject()
				.put("type", "string")
				.put("enum", new JSONArray()
						.put("think_and_plan")
						.put("execute_tool")
						.put("confirm_tool_results")
						.put("check_complete")
						.put("final"))
				.put("description",
						"Use execute_tool whenever you are ready to act; set tool_call.name in that same response. "
								+ "Do not use think_and_plan or confirm_tool_results to repeat a decision—call the tool. "
								+ "After a tool result, the next turn must call another tool or done."));
		properties.put("thought", new JSONObject()
				.put("type", "string")
				.put("description", "One short sentence only. Do not repeat prior thoughts."));
		properties.put("plan", new JSONObject()
				.put("type", "string")
				.put("description",
						"Next action in one line, or empty. If you know the tool, use step=execute_tool with tool_call.name instead of replanning."));
		properties.put("tool_call", toolSelectionSchema(includeSubtask));
		properties.put("complete", new JSONObject()
				.put("type", "boolean")
				.put("description", "True only when the user's task is fully complete."));
		properties.put("response", new JSONObject()
				.put("type", "string")
				.put("description", "Final or interim user-facing response. Keep empty until final unless useful."));
		schema.put("properties", properties);

		return schema;
	}

	/**
	 * JSON schema for the second Ollama call: arguments for the tool chosen in step one.
	 * Returns null if the tool name is unknown.
	 */
	public static JSONObject buildToolArgumentsSchema(String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return null;
		}
		return switch (toolName) {
		case "read" -> argumentsSchema(
				new JSONArray().put("path"),
				new JSONObject().put("path", stringProperty("Relative or absolute file path to read.")));
		case "write" -> argumentsSchema(
				new JSONArray().put("path").put("content"),
				new JSONObject()
						.put("path", stringProperty("Relative or absolute file path to create or overwrite."))
						.put("content", stringProperty("Full file contents.")));
		case "edit" -> argumentsSchema(
				new JSONArray().put("path").put("old").put("new"),
				new JSONObject()
						.put("path", stringProperty("Relative or absolute file path to edit."))
						.put("old", stringProperty("Exact text to replace (must appear once in the file)."))
						.put("new", stringProperty("Replacement text.")));
		case "bash" -> argumentsSchema(
				new JSONArray().put("command"),
				new JSONObject()
						.put("command", stringProperty("Shell command to run in the project directory."))
						.put("timeout_seconds", new JSONObject()
								.put("type", "number")
								.put("minimum", 0)
								.put("description",
										"Optional. Seconds before the command is killed. "
												+ "Omit for the 60 second default. 0 means no timeout.")));
		case "run" -> argumentsSchema(
				new JSONArray(),
				new JSONObject().put("command", stringProperty(
						"Optional shell command override. Omit or use empty string to run run.sh/run.bat.")));
		case "subtask" -> argumentsSchema(
				new JSONArray().put("task"),
				new JSONObject()
						.put("task", stringProperty("Self-contained goal for the worker agent."))
						.put("context", stringProperty("Optional brief context from the master agent.")));
		case "done" -> argumentsSchema(
				new JSONArray().put("response"),
				new JSONObject().put("response", stringProperty("Final response to the user or master agent.")));
		default -> null;
		};
	}

	public static boolean isKnownTool(String toolName, boolean includeSubtask) {
		if (toolName == null || toolName.isBlank()) {
			return false;
		}
		return switch (toolName) {
		case "read", "bash", "run", "edit", "write", "done" -> true;
		case "subtask" -> includeSubtask;
		default -> false;
		};
	}

	private static JSONObject toolSelectionSchema(boolean includeSubtask) {
		JSONObject call = object();
		call.put("additionalProperties", false);
		call.put("required", new JSONArray().put("name"));
		JSONArray toolNames = new JSONArray()
				.put("read")
				.put("bash")
				.put("run")
				.put("edit")
				.put("write");
		if (includeSubtask) {
			toolNames.put("subtask");
		}
		toolNames.put("done");
		call.put("properties", new JSONObject()
				.put("name", new JSONObject()
						.put("type", "string")
						.put("enum", toolNames)
						.put("description", "Tool to run on this step. Arguments are collected in the next request.")));

		return new JSONObject()
				.put("anyOf", new JSONArray()
						.put(call)
						.put(new JSONObject().put("type", "null")))
				.put("description",
						"Exactly one tool to execute this step, or null when no tool is needed. "
								+ "Set only the tool name; arguments are supplied separately.");
	}

	private static JSONObject argumentsSchema(JSONArray required, JSONObject properties) {
		JSONObject schema = object();
		schema.put("additionalProperties", false);
		schema.put("required", required);
		schema.put("properties", properties);
		schema.put("description", "Arguments for the selected tool.");
		return schema;
	}

	private static JSONObject stringProperty(String description) {
		return new JSONObject()
				.put("type", "string")
				.put("description", description);
	}

	private static JSONObject object() {
		return new JSONObject().put("type", "object");
	}
}
