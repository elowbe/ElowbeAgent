package com.elowbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public class ToolChoiceSchema {
	private ToolChoiceSchema() {
	}

	public static JSONObject build() {
		return build(true);
	}

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
						.put("final")));
		properties.put("thought", new JSONObject()
				.put("type", "string")
				.put("description", "Concise private progress note for the current step."));
		properties.put("plan", new JSONObject()
				.put("type", "string")
				.put("description", "Brief plan for the current step. Use an empty string when not needed."));
		properties.put("tool_call", toolCallSchema(includeSubtask));
		properties.put("complete", new JSONObject()
				.put("type", "boolean")
				.put("description", "True only when the user's task is fully complete."));
		properties.put("response", new JSONObject()
				.put("type", "string")
				.put("description", "Final or interim user-facing response. Keep empty until final unless useful."));
		schema.put("properties", properties);

		return schema;
	}

	private static JSONObject toolCallSchema(boolean includeSubtask) {
		JSONObject call = object();
		call.put("additionalProperties", false);
		call.put("required", new JSONArray().put("name").put("arguments"));
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
						.put("enum", toolNames))
				.put("arguments", toolArgumentsSchema()));

		return new JSONObject()
				.put("anyOf", new JSONArray()
						.put(call)
						.put(new JSONObject().put("type", "null")))
				.put("description", "Exactly one tool to execute this step, or null when no tool is needed.");
	}

	private static JSONObject toolArgumentsSchema() {
		JSONObject schema = object();
		schema.put("description",
				"Tool-specific arguments. Include only the fields required by the chosen tool.");
		JSONObject properties = new JSONObject();
		properties.put("path", stringProperty("File path for read, edit, or write."));
		properties.put("command", stringProperty("Shell command for bash or run."));
		properties.put("timeout_seconds", new JSONObject()
				.put("type", "number")
				.put("minimum", 0)
				.put("description",
						"Optional (bash only). Seconds before the command is killed. "
								+ "Omit to use the 60 second default. 0 means no timeout. "
								+ "Do not wrap the command in a shell timeout utility."));
		properties.put("old", stringProperty("Exact text to replace for edit."));
		properties.put("new", stringProperty("Replacement text for edit."));
		properties.put("content", stringProperty("Full file contents for write."));
		properties.put("task", stringProperty("Self-contained goal for subtask."));
		properties.put("context", stringProperty("Optional brief context for subtask."));
		properties.put("response", stringProperty("Final message for done."));
		schema.put("properties", properties);
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
