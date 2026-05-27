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
				.put("tool_call")
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
				.put("description",
						"One short sentence: why you are calling this tool now. The argument step will receive this."));
		properties.put("plan", new JSONObject()
				.put("type", "string")
				.put("description",
						"What this tool call will do. When selecting a tool, include the intended arguments here "
								+ "(e.g. maven init with artifact_id lwjgl-cube). The argument step must follow this plan."));
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
				new JSONArray().put("path").put("start_line").put("end_line").put("new"),
				new JSONObject()
						.put("path", stringProperty("Relative or absolute file path to edit."))
						.put("start_line", integerProperty(
								"1-based start line (inclusive). Use read output line numbers."))
						.put("end_line", new JSONObject()
								.put("type", "integer")
								.put("minimum", 0)
								.put("description",
										"1-based end line (inclusive). Set to start_line - 1 to insert without deleting."))
						.put("new", stringProperty("Replacement text for the line range. Use empty string to delete lines.")));
		case "bash" -> argumentsSchema(
				new JSONArray().put("command"),
				new JSONObject()
						.put("command", stringProperty(
								"Shell command to run in the project directory. Do not use bash for browser/web tasks that the web tool can perform."))
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
		case "web" -> buildWebArgumentsSchema();
		case "subtask" -> argumentsSchema(
				new JSONArray().put("task"),
				new JSONObject()
						.put("task", stringProperty(
								"Exactly one atomic action for the worker (one read, one edit, one search, one maven action, etc.). "
										+ "Do not pass a multi-step plan or checklist."))
						.put("context", stringProperty("Optional brief context from the master agent.")));
		case "done" -> argumentsSchema(
				new JSONArray().put("response"),
				new JSONObject().put("response", stringProperty("Final response to the user or master agent.")));
		case "maven" -> buildMavenArgumentsSchema();
		default -> null;
		};
	}

	public static boolean isKnownTool(String toolName, boolean includeSubtask) {
		if (toolName == null || toolName.isBlank()) {
			return false;
		}
		return switch (toolName) {
		case "read", "bash", "run", "edit", "write", "maven", "web", "done" -> true;
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
				.put("write")
				.put("maven")
				.put("web");
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

	private static JSONObject integerProperty(String description) {
		return new JSONObject()
				.put("type", "integer")
				.put("minimum", 1)
				.put("description", description);
	}

	private static JSONObject object() {
		return new JSONObject().put("type", "object");
	}

	private static JSONObject buildWebArgumentsSchema() {
		JSONObject properties = new JSONObject();
		properties.put("action", new JSONObject()
				.put("type", "string")
				.put("enum", new JSONArray()
						.put("open")
						.put("follow")
						.put("search")
						.put("api"))
				.put("description",
						"Use open to load a page and extract text/links, follow to load a URL then follow a link, "
								+ "search to search the web with fallback providers, api to test an HTTP API from a browser context."));
		properties.put("url", stringProperty("Page URL or API endpoint. Required for open/follow/api."));
		properties.put("query", stringProperty("Search query. Required for search."));
		properties.put("link_url", stringProperty("For follow: exact URL/href to follow after loading url."));
		properties.put("link_text", stringProperty("For follow: click the first link containing this visible text."));
		properties.put("link_index", new JSONObject()
				.put("type", "integer")
				.put("minimum", 0)
				.put("description", "For follow: zero-based index into the page's discovered links."));
		properties.put("method", stringProperty("For api: HTTP method. Defaults to GET."));
		properties.put("headers", new JSONObject()
				.put("type", "object")
				.put("additionalProperties", new JSONObject().put("type", "string"))
				.put("description", "For api: request headers."));
		properties.put("body", stringProperty("For api: request body string."));
		properties.put("max_links", new JSONObject()
				.put("type", "integer")
				.put("minimum", 0)
				.put("description", "Maximum number of links to return. Default 30."));
		properties.put("timeout_seconds", new JSONObject()
				.put("type", "number")
				.put("minimum", 1)
				.put("description", "Browser page-load and script timeout in seconds. Default 30."));
		return argumentsSchema(new JSONArray().put("action"), properties);
	}

	private static JSONObject buildMavenArgumentsSchema() {
		JSONObject dependency = mavenDependencySchema();
		JSONObject plugin = mavenPluginSchema();

		JSONObject properties = mavenCommonProperties(dependency, plugin);
		properties.put("action", new JSONObject()
				.put("type", "string")
				.put("enum", new JSONArray()
						.put("init")
						.put("info")
						.put("configure")
						.put("compile")
						.put("test")
						.put("package")
						.put("goal"))
				.put("description",
						"Required. Choose the action from the selection step's thought/plan. "
								+ "Use init to create a project, configure to change pom.xml, "
								+ "compile/test/package for builds, goal for custom Maven goals, info only to inspect the current pom."));
		properties.put("group_id", stringProperty(
				"For init/configure. Maven groupId, e.g. com.elowbe. Required for init unless inferred by the tool."));
		properties.put("artifact_id", stringProperty(
				"For init/configure. Maven artifactId slug, e.g. lwjgl-cube. Required for init unless inferred by the tool."));
		properties.put("goals", new JSONObject()
				.put("type", "array")
				.put("items", new JSONObject().put("type", "string"))
				.put("description", "For goal: Maven goals/phases, e.g. [\"clean\", \"verify\"] or [\"javafx:run\"]."));
		properties.put("goal", stringProperty("For goal: single Maven goal/phase when goals array is omitted."));
		properties.put("quiet", new JSONObject()
				.put("type", "boolean")
				.put("description", "For compile/test/package/goal. Default true (uses mvn -q)."));
		properties.put("skip_tests", new JSONObject()
				.put("type", "boolean")
				.put("description", "For compile/test/package/goal. Adds -DskipTests when true."));
		properties.put("args", stringProperty("Extra Maven CLI args, e.g. \"-DskipTests -X\"."));
		properties.put("timeout_seconds", new JSONObject()
				.put("type", "number")
				.put("minimum", 0)
				.put("description", "Seconds before Maven is killed. Default 300. 0 means no timeout."));

		return argumentsSchema(new JSONArray().put("action"), properties);
	}

	private static JSONObject mavenCommonProperties(JSONObject dependency, JSONObject plugin) {
		JSONObject properties = new JSONObject()
				.put("action", new JSONObject().put("type", "string"))
				.put("version", stringProperty("Project version for init/configure."))
				.put("java_version", stringProperty("Java version for init, default 17."))
				.put("name", stringProperty("Project display name for init/configure."))
				.put("description", stringProperty("Project description for init/configure."))
				.put("packaging", stringProperty("Project packaging for init/configure, default jar."))
				.put("properties", new JSONObject()
						.put("type", "object")
						.put("additionalProperties", new JSONObject().put("type", "string"))
						.put("description", "For configure: property name to value map."))
				.put("add_dependencies", new JSONObject()
						.put("type", "array")
						.put("items", dependency)
						.put("description", "For configure: dependencies to add or update."))
				.put("remove_dependencies", new JSONObject()
						.put("type", "array")
						.put("items", dependency)
						.put("description", "For configure: dependencies to remove by group_id and artifact_id."))
				.put("add_plugins", new JSONObject()
						.put("type", "array")
						.put("items", plugin)
						.put("description", "For configure: plugins to add or update."))
				.put("remove_plugins", new JSONObject()
						.put("type", "array")
						.put("items", plugin)
						.put("description", "For configure: plugins to remove by group_id and artifact_id."));
		return properties;
	}

	private static JSONObject mavenBuildProperties() {
		return new JSONObject()
				.put("action", new JSONObject().put("type", "string"))
				.put("goals", new JSONObject()
						.put("type", "array")
						.put("items", new JSONObject().put("type", "string"))
						.put("description", "For goal: Maven goals/phases, e.g. [\"clean\", \"verify\"] or [\"javafx:run\"]."))
				.put("goal", stringProperty("For goal: single Maven goal/phase when goals array is omitted."))
				.put("quiet", new JSONObject()
						.put("type", "boolean")
						.put("description", "For compile/test/package/goal. Default true (uses mvn -q)."))
				.put("skip_tests", new JSONObject()
						.put("type", "boolean")
						.put("description", "For compile/test/package/goal. Adds -DskipTests when true."))
				.put("args", stringProperty("Extra Maven CLI args, e.g. \"-DskipTests -X\"."))
				.put("timeout_seconds", new JSONObject()
						.put("type", "number")
						.put("minimum", 0)
						.put("description", "Seconds before Maven is killed. Default 300. 0 means no timeout."));
	}

	private static JSONObject mavenDependencySchema() {
		JSONObject dependency = object();
		dependency.put("additionalProperties", false);
		dependency.put("properties", new JSONObject()
				.put("group_id", stringProperty("Maven groupId."))
				.put("artifact_id", stringProperty("Maven artifactId."))
				.put("version", stringProperty("Dependency or plugin version."))
				.put("scope", stringProperty("Optional dependency scope, e.g. compile, test, provided."))
				.put("type", stringProperty("Optional dependency type, e.g. jar or pom."))
				.put("classifier", stringProperty("Optional dependency classifier."))
				.put("optional", new JSONObject()
						.put("type", "boolean")
						.put("description", "Whether the dependency is optional.")));
		return dependency;
	}

	private static JSONObject mavenPluginSchema() {
		JSONObject pluginExecution = object();
		pluginExecution.put("additionalProperties", false);
		pluginExecution.put("properties", new JSONObject()
				.put("id", stringProperty("Optional execution id."))
				.put("phase", stringProperty("Optional Maven phase, e.g. package."))
				.put("goals", new JSONObject()
						.put("type", "array")
						.put("items", new JSONObject().put("type", "string"))
						.put("description", "Goals for this execution, e.g. [\"shade\"]."))
				.put("configuration", stringProperty("Optional inner XML placed under <configuration>.")));

		JSONObject plugin = object();
		plugin.put("additionalProperties", false);
		plugin.put("properties", new JSONObject()
				.put("group_id", stringProperty("Plugin groupId. Defaults to org.apache.maven.plugins; exec-maven-plugin uses org.codehaus.mojo; javafx-maven-plugin uses org.openjfx."))
				.put("artifact_id", stringProperty("Plugin artifactId."))
				.put("version", stringProperty("Plugin version."))
				.put("main_class", stringProperty("Preferred for exec-maven-plugin/javafx-maven-plugin. Sets <mainClass> without XML."))
				.put("configuration", stringProperty("Optional inner XML placed under <configuration>."))
				.put("executions", new JSONObject()
						.put("type", "array")
						.put("items", pluginExecution)
						.put("description", "Optional plugin executions.")));
		return plugin;
	}

	private static JSONObject requiredStringProperty(String description) {
		return new JSONObject()
				.put("type", "string")
				.put("minLength", 1)
				.put("description", description);
	}
}
