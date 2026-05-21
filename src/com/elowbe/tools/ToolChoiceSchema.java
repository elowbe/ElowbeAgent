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
				.put("tool_calls")
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
		properties.put("tool_calls", toolCallsSchema(includeSubtask));
		properties.put("complete", new JSONObject()
				.put("type", "boolean")
				.put("description", "True only when the user's task is fully complete."));
		properties.put("response", new JSONObject()
				.put("type", "string")
				.put("description", "Final or interim user-facing response. Keep empty until final unless useful."));
		schema.put("properties", properties);

		return schema;
	}

	private static JSONObject toolCallsSchema(boolean includeSubtask) {
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
				.put("arguments", new JSONObject()
						.put("type", "object")
						.put("description", "Tool-specific arguments.")));

		return new JSONObject()
				.put("type", "array")
				.put("description", "Zero or more tools to execute for this step.")
				.put("items", call);
	}

	private static JSONObject object() {
		return new JSONObject().put("type", "object");
	}
}
