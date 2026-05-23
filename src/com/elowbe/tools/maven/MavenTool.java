package com.elowbe.tools.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import com.elowbe.tools.ToolResult;

public final class MavenTool {
	private static final int MAX_OUTPUT_CHARS = 40_000;
	private static final Duration DEFAULT_MAVEN_TIMEOUT = Duration.ofMinutes(5);
	private static final BooleanSupplier NEVER_CANCEL = () -> false;

	private MavenTool() {
	}

	public static ToolResult execute(JSONObject arguments, File workingDirectory) {
		return execute(arguments, workingDirectory, NEVER_CANCEL);
	}

	public static ToolResult execute(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested) {
		if (arguments == null) {
			arguments = new JSONObject();
		}
		if (cancelRequested == null) {
			cancelRequested = NEVER_CANCEL;
		}

		String action = first(arguments, "action").trim().toLowerCase(Locale.ROOT);
		if (action.isBlank()) {
			return ToolResult.output("maven: missing action");
		}

		try {
			return switch (action) {
			case "init" -> init(arguments, workingDirectory);
			default -> {
				File projectRoot = resolveProjectRoot(workingDirectory);
				yield switch (action) {
				case "info" -> info(projectRoot);
				case "configure" -> configure(arguments, projectRoot);
				case "compile" -> runMaven(projectRoot, List.of("compile"), arguments, cancelRequested);
				case "test" -> runMaven(projectRoot, List.of("test"), arguments, cancelRequested);
				case "package" -> runMaven(projectRoot, List.of("package"), arguments, cancelRequested);
				case "goal", "goals" -> runCustomGoals(arguments, projectRoot, cancelRequested);
				default -> ToolResult.output("maven: unknown action \"" + action
						+ "\". Use init, info, configure, compile, test, package, or goal.");
				};
			}
			};
		} catch (IOException e) {
			return ToolResult.output("maven: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ToolResult.output("maven: interrupted");
		}
	}

	private static ToolResult init(JSONObject arguments, File workingDirectory) throws IOException {
		File base = workingDirectory == null ? new File(System.getProperty("user.dir")) : workingDirectory;
		File pomFile = new File(base, "pom.xml");
		MavenInitCoordinates coordinates = MavenInitCoordinates.resolve(arguments, base);
		if (!coordinates.isValid()) {
			return ToolResult.output(coordinates.validationError());
		}
		MavenInitCoordinates.apply(arguments, coordinates);

		String groupId = coordinates.groupId();
		String artifactId = coordinates.artifactId();
		String version = first(arguments, "version");
		String javaVersion = first(arguments, "java_version", "javaVersion");
		String name = first(arguments, "name");
		String description = first(arguments, "description");
		String packaging = first(arguments, "packaging");

		PomModel.createNew(pomFile, groupId, artifactId, version, javaVersion, name, description, packaging);
		createStandardLayout(base);

		StringBuilder result = new StringBuilder();
		result.append(MavenInitCoordinates.formatInferenceNote(coordinates));
		result.append("maven init: created project at ").append(base.getCanonicalPath()).append('\n');
		result.append("- pom.xml\n");
		result.append("- src/main/java\n");
		result.append("- src/main/resources\n");
		result.append("- src/test/java\n");
		PomModel model = PomModel.load(pomFile);
		result.append('\n').append(model.summarize());
		return ToolResult.output(result.toString().trim());
	}

	private static ToolResult info(File projectRoot) throws IOException {
		PomModel model = PomModel.load(new File(projectRoot, "pom.xml"));
		return ToolResult.output(model.summarize());
	}

	private static ToolResult configure(JSONObject arguments, File projectRoot) throws IOException {
		File pomFile = new File(projectRoot, "pom.xml");
		PomModel model = PomModel.load(pomFile);
		int changes = 0;

		Map<String, String> coordinates = PomModel.parseCoordinates(arguments);
		for (Map.Entry<String, String> entry : coordinates.entrySet()) {
			model.setCoordinate(entry.getKey(), entry.getValue());
			changes++;
		}

		if (arguments.has("properties") && !arguments.isNull("properties")) {
			JSONObject properties = arguments.getJSONObject("properties");
			for (String key : properties.keySet()) {
				model.setProperty(key, String.valueOf(properties.get(key)));
				changes++;
			}
		}

		if (arguments.has("add_dependencies") && !arguments.isNull("add_dependencies")) {
			JSONArray dependencies = arguments.getJSONArray("add_dependencies");
			for (int i = 0; i < dependencies.length(); i++) {
				model.addDependency(parseDependency(dependencies.getJSONObject(i)));
				changes++;
			}
		}

		if (arguments.has("remove_dependencies") && !arguments.isNull("remove_dependencies")) {
			JSONArray dependencies = arguments.getJSONArray("remove_dependencies");
			for (int i = 0; i < dependencies.length(); i++) {
				JSONObject dependency = dependencies.getJSONObject(i);
				if (model.removeDependency(first(dependency, "group_id", "groupId"),
						first(dependency, "artifact_id", "artifactId"))) {
					changes++;
				}
			}
		}

		if (arguments.has("add_plugins") && !arguments.isNull("add_plugins")) {
			JSONArray plugins = arguments.getJSONArray("add_plugins");
			for (int i = 0; i < plugins.length(); i++) {
				model.addPlugin(parsePlugin(plugins.getJSONObject(i)));
				changes++;
			}
		}

		if (arguments.has("remove_plugins") && !arguments.isNull("remove_plugins")) {
			JSONArray plugins = arguments.getJSONArray("remove_plugins");
			for (int i = 0; i < plugins.length(); i++) {
				JSONObject plugin = plugins.getJSONObject(i);
				if (model.removePlugin(first(plugin, "group_id", "groupId"),
						first(plugin, "artifact_id", "artifactId"))) {
					changes++;
				}
			}
		}

		if (changes == 0) {
			return ToolResult.output("maven configure: no changes requested");
		}

		model.save();
		StringBuilder result = new StringBuilder();
		result.append("maven configure: updated ").append(changes).append(" item(s)\n");
		result.append(model.summarize());
		return ToolResult.output(result.toString().trim());
	}

	private static ToolResult runCustomGoals(JSONObject arguments, File projectRoot, BooleanSupplier cancelRequested)
			throws IOException, InterruptedException {
		List<String> goals = new ArrayList<>();
		if (arguments.has("goals") && !arguments.isNull("goals")) {
			Object rawGoals = arguments.get("goals");
			if (rawGoals instanceof JSONArray array) {
				for (int i = 0; i < array.length(); i++) {
					String goal = String.valueOf(array.get(i)).trim();
					if (!goal.isBlank()) {
						goals.add(goal);
					}
				}
			} else {
				for (String goal : String.valueOf(rawGoals).split("\\s+")) {
					if (!goal.isBlank()) {
						goals.add(goal.trim());
					}
				}
			}
		}
		if (goals.isEmpty()) {
			String goal = first(arguments, "goal");
			if (!goal.isBlank()) {
				goals.add(goal);
			}
		}
		if (goals.isEmpty()) {
			return ToolResult.output("maven goal: missing goals");
		}
		return runMaven(projectRoot, goals, arguments, cancelRequested);
	}

	private static ToolResult runMaven(File projectRoot, List<String> goals, JSONObject arguments,
			BooleanSupplier cancelRequested) throws IOException, InterruptedException {
		String shellCommand = buildMavenShellCommand(goals, arguments);
		Duration timeout = resolveTimeout(arguments);
		if (cancelRequested.getAsBoolean()) {
			return ToolResult.output("maven: cancelled");
		}

		ProcessBuilder builder = new ProcessBuilder("/bin/zsh", "-lc", shellCommand);
		builder.directory(projectRoot);
		builder.redirectErrorStream(false);
		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			return ToolResult.output("maven: failed to start Maven.\n"
					+ "command: " + shellCommand + "\n"
					+ "error: " + e.getMessage() + "\n"
					+ "Ensure mvn is installed and available in a login shell (e.g. /opt/homebrew/bin/mvn).");
		}

		StreamCollector stdout = new StreamCollector(process.getInputStream());
		StreamCollector stderr = new StreamCollector(process.getErrorStream());
		Thread outThread = new Thread(stdout, "elowbe-maven-stdout");
		Thread errThread = new Thread(stderr, "elowbe-maven-stderr");
		outThread.start();
		errThread.start();

		boolean completed = false;
		boolean timedOut = false;
		boolean cancelled = false;
		Long deadline = timeout == null ? null : System.nanoTime() + timeout.toNanos();
		try {
			while (true) {
				if (cancelRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
					cancelled = true;
					process.destroyForcibly();
					break;
				}
				if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
					completed = true;
					break;
				}
				if (deadline != null && System.nanoTime() >= deadline) {
					timedOut = true;
					process.destroyForcibly();
					break;
				}
			}
		} finally {
			process.destroyForcibly();
		}

		outThread.join();
		errThread.join();

		int exitCode = completed ? process.exitValue() : -1;
		StringBuilder result = new StringBuilder();
		result.append("maven ").append(String.join(" ", goals));
		result.append(" (exit ").append(exitCode).append(")\n");
		result.append("command: ").append(shellCommand).append('\n');
		result.append("project: ").append(projectRoot.getPath()).append('\n');
		if (cancelled) {
			result.append("Cancelled\n");
		} else if (timedOut && timeout != null) {
			result.append("Timed out after ").append(timeout.toSeconds()).append(" seconds\n");
		}
		if (completed && exitCode == 127) {
			result.append("FAILED: mvn was not found. Install Maven or ensure it is on PATH in a login shell.\n");
		} else if (completed && exitCode != 0) {
			result.append("FAILED: Maven exited with code ").append(exitCode).append(".\n");
		} else if (completed) {
			result.append("SUCCESS\n");
		}
		if (!stdout.text().isBlank()) {
			result.append("stdout:\n").append(stdout.text()).append('\n');
		}
		if (!stderr.text().isBlank()) {
			result.append("stderr:\n").append(stderr.text()).append('\n');
		}
		if (completed && exitCode != 0 && stdout.text().isBlank() && stderr.text().isBlank()) {
			result.append("Maven failed with no output.\n");
		}
		return ToolResult.output(truncate(result.toString().trim()));
	}

	private static String buildMavenShellCommand(List<String> goals, JSONObject arguments) {
		StringBuilder command = new StringBuilder("mvn");
		if (parseBoolean(arguments, "quiet", true)) {
			command.append(" -q");
		}
		if (parseBoolean(arguments, "skip_tests", false)) {
			command.append(" -DskipTests");
		}
		String extraArgs = first(arguments, "args", "maven_args");
		if (!extraArgs.isBlank()) {
			command.append(' ').append(extraArgs.trim());
		}
		for (String goal : goals) {
			if (goal != null && !goal.isBlank()) {
				command.append(' ').append(shellQuote(goal.trim()));
			}
		}
		return command.toString();
	}

	private static String shellQuote(String value) {
		if (value.matches("[A-Za-z0-9_./:@+-]+")) {
			return value;
		}
		return "'" + value.replace("'", "'\\''") + "'";
	}

	private static PomModel.DependencySpec parseDependency(JSONObject object) {
		return new PomModel.DependencySpec(
				first(object, "group_id", "groupId"),
				first(object, "artifact_id", "artifactId"),
				first(object, "version"),
				first(object, "scope"),
				first(object, "type"),
				first(object, "classifier"),
				parseBoolean(object, "optional", false));
	}

	private static PomModel.PluginSpec parsePlugin(JSONObject object) throws IOException {
		List<PomModel.PluginExecutionSpec> executions = new ArrayList<>();
		if (object.has("executions") && !object.isNull("executions")) {
			JSONArray rawExecutions = object.getJSONArray("executions");
			for (int i = 0; i < rawExecutions.length(); i++) {
				JSONObject execution = rawExecutions.getJSONObject(i);
				List<String> goals = new ArrayList<>();
				if (execution.has("goals") && !execution.isNull("goals")) {
					JSONArray rawGoals = execution.getJSONArray("goals");
					for (int j = 0; j < rawGoals.length(); j++) {
						goals.add(String.valueOf(rawGoals.get(j)));
					}
				}
				executions.add(new PomModel.PluginExecutionSpec(
						first(execution, "id"),
						first(execution, "phase"),
						goals,
						first(execution, "configuration", "configuration_xml")));
			}
		}
		return new PomModel.PluginSpec(
				first(object, "group_id", "groupId"),
				first(object, "artifact_id", "artifactId"),
				first(object, "version"),
				first(object, "configuration", "configuration_xml"),
				executions);
	}

	private static void createStandardLayout(File base) throws IOException {
		Files.createDirectories(base.toPath().resolve("src/main/java"));
		Files.createDirectories(base.toPath().resolve("src/main/resources"));
		Files.createDirectories(base.toPath().resolve("src/test/java"));
	}

	private static File resolveProjectRoot(File workingDirectory) throws IOException {
		File base = workingDirectory == null ? new File(System.getProperty("user.dir")) : workingDirectory;
		File current = base.getCanonicalFile();
		while (current != null) {
			File pom = new File(current, "pom.xml");
			if (pom.isFile()) {
				return current;
			}
			current = current.getParentFile();
		}
		if (new File(base, "pom.xml").isFile()) {
			return base.getCanonicalFile();
		}
		throw new IOException("no pom.xml found in " + base.getPath() + " or parent directories");
	}

	private static Duration resolveTimeout(JSONObject arguments) {
		if (arguments == null || !arguments.has("timeout_seconds") || arguments.isNull("timeout_seconds")) {
			return DEFAULT_MAVEN_TIMEOUT;
		}
		Object raw = arguments.get("timeout_seconds");
		double seconds;
		if (raw instanceof Number number) {
			seconds = number.doubleValue();
		} else {
			try {
				seconds = Double.parseDouble(String.valueOf(raw).trim());
			} catch (NumberFormatException e) {
				return DEFAULT_MAVEN_TIMEOUT;
			}
		}
		if (!Double.isFinite(seconds) || seconds < 0) {
			return DEFAULT_MAVEN_TIMEOUT;
		}
		if (seconds == 0) {
			return null;
		}
		long millis = Math.max(1L, Math.round(seconds * 1000.0));
		return Duration.ofMillis(millis);
	}

	private static boolean parseBoolean(JSONObject object, String key, boolean defaultValue) {
		if (object == null || !object.has(key) || object.isNull(key)) {
			return defaultValue;
		}
		Object raw = object.get(key);
		if (raw instanceof Boolean bool) {
			return bool;
		}
		String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
		if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) {
			return true;
		}
		if ("false".equals(text) || "no".equals(text) || "0".equals(text)) {
			return false;
		}
		return defaultValue;
	}

	private static String first(JSONObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key));
			}
		}
		return "";
	}

	/** Hint for the argument-resolution call after the agent selects the maven tool. */
	public static String buildArgumentPrompt() {
		return """
				Return one JSON object with only the maven tool arguments.
				Do not wrap arguments in a step object or tool_call.

				For action init, group_id and artifact_id are REQUIRED separate fields.
				artifact_id must be a lowercase Maven slug (e.g. lwjgl-cube). The name field is display text only.
				Example init:
				{"action":"init","group_id":"com.elowbe","artifact_id":"lwjgl-cube","java_version":"21","name":"LWJGL Cube"}

				For compile, test, package, or goal, only action is required unless you need extra flags.
				""".trim();
	}

	/** Multi-line description of a maven tool invocation for console logging. */
	public static String describeAction(JSONObject arguments) {
		if (arguments == null) {
			arguments = new JSONObject();
		}
		String action = first(arguments, "action").trim().toLowerCase(Locale.ROOT);
		if (action.isBlank()) {
			return "→ maven";
		}

		StringBuilder description = new StringBuilder("→ maven ").append(action);
		switch (action) {
		case "init" -> appendInitAction(description, arguments);
		case "configure" -> appendConfigureAction(description, arguments);
		case "goal", "goals" -> appendGoalAction(description, arguments);
		case "compile", "test", "package" -> appendBuildAction(description, arguments);
		default -> {
		}
		}
		return description.toString();
	}

	/** Concise result summary for console logging after a maven tool run. */
	public static String describeResultSummary(String result) {
		if (result == null || result.isBlank()) {
			return "  (no output)";
		}
		if (result.startsWith("maven:")) {
			return "  ✗ " + result.substring("maven:".length()).trim();
		}
		String firstLine = result.lines().findFirst().orElse("").trim();
		if (firstLine.startsWith("maven configure:") || firstLine.startsWith("maven init:")
				|| (firstLine.equals(result.trim()) && firstLine.startsWith("pom:"))) {
			return formatPomResultSummary(result);
		}
		if (firstLine.startsWith("maven ") && firstLine.contains("(exit ")) {
			return formatBuildResultSummary(result);
		}
		return formatPomResultSummary(result);
	}

	private static void appendInitAction(StringBuilder description, JSONObject arguments) {
		MavenInitCoordinates coordinates = MavenInitCoordinates.resolve(arguments, null);
		String groupId = first(arguments, "group_id", "groupId");
		String artifactId = first(arguments, "artifact_id", "artifactId");
		if (groupId.isBlank()) {
			groupId = coordinates.groupId();
		}
		if (artifactId.isBlank()) {
			artifactId = coordinates.artifactId();
		}
		description.append('\n').append("  coordinates: ")
				.append(groupId.isBlank() ? "?" : groupId).append(':')
				.append(artifactId.isBlank() ? "?" : artifactId);
		if (first(arguments, "artifact_id", "artifactId").isBlank() && !artifactId.isBlank()) {
			description.append(" (will infer artifact_id)");
		}
		appendOptionalLine(description, "version", first(arguments, "version"));
		appendOptionalLine(description, "java_version", first(arguments, "java_version", "javaVersion"));
		appendOptionalLine(description, "packaging", first(arguments, "packaging"));
		appendOptionalLine(description, "name", first(arguments, "name"));
		appendOptionalLine(description, "description", first(arguments, "description"));
	}

	private static void appendConfigureAction(StringBuilder description, JSONObject arguments) {
		Map<String, String> coordinates = PomModel.parseCoordinates(arguments);
		for (Map.Entry<String, String> entry : coordinates.entrySet()) {
			description.append('\n').append("  ").append(entry.getKey()).append('=').append(entry.getValue());
		}

		if (arguments.has("properties") && !arguments.isNull("properties")) {
			JSONObject properties = arguments.getJSONObject("properties");
			for (String key : properties.keySet()) {
				description.append('\n').append("  + property ").append(key).append('=')
						.append(properties.get(key));
			}
		}

		appendDependencyList(description, "+ dependency ", arguments, "add_dependencies");
		appendDependencyList(description, "- dependency ", arguments, "remove_dependencies");
		appendPluginList(description, "+ plugin ", arguments, "add_plugins");
		appendPluginList(description, "- plugin ", arguments, "remove_plugins");
	}

	private static void appendGoalAction(StringBuilder description, JSONObject arguments) {
		List<String> goals = readGoals(arguments);
		if (!goals.isEmpty()) {
			description.append('\n').append("  goals: ").append(String.join(" ", goals));
		}
		appendBuildFlags(description, arguments);
	}

	private static void appendBuildAction(StringBuilder description, JSONObject arguments) {
		appendBuildFlags(description, arguments);
	}

	private static void appendBuildFlags(StringBuilder description, JSONObject arguments) {
		if (!parseBoolean(arguments, "quiet", true)) {
			description.append('\n').append("  quiet: false");
		}
		if (parseBoolean(arguments, "skip_tests", false)) {
			description.append('\n').append("  skip_tests: true");
		}
		String args = first(arguments, "args", "maven_args");
		if (!args.isBlank()) {
			description.append('\n').append("  args: ").append(args);
		}
	}

	private static void appendDependencyList(StringBuilder description, String prefix, JSONObject arguments,
			String key) {
		if (!arguments.has(key) || arguments.isNull(key)) {
			return;
		}
		JSONArray dependencies = arguments.getJSONArray(key);
		for (int i = 0; i < dependencies.length(); i++) {
			description.append('\n').append("  ").append(prefix).append(formatDependency(dependencies.getJSONObject(i)));
		}
	}

	private static void appendPluginList(StringBuilder description, String prefix, JSONObject arguments, String key) {
		if (!arguments.has(key) || arguments.isNull(key)) {
			return;
		}
		JSONArray plugins = arguments.getJSONArray(key);
		for (int i = 0; i < plugins.length(); i++) {
			description.append('\n').append("  ").append(prefix).append(formatPlugin(plugins.getJSONObject(i)));
		}
	}

	private static String formatDependency(JSONObject dependency) {
		String groupId = first(dependency, "group_id", "groupId");
		String artifactId = first(dependency, "artifact_id", "artifactId");
		String version = first(dependency, "version");
		String scope = first(dependency, "scope");
		String type = first(dependency, "type");
		String classifier = first(dependency, "classifier");
		StringBuilder label = new StringBuilder();
		label.append(groupId.isBlank() ? "?" : groupId).append(':')
				.append(artifactId.isBlank() ? "?" : artifactId);
		if (!version.isBlank()) {
			label.append(':').append(version);
		}
		if (!scope.isBlank()) {
			label.append(" scope=").append(scope);
		}
		if (!type.isBlank()) {
			label.append(" type=").append(type);
		}
		if (!classifier.isBlank()) {
			label.append(" classifier=").append(classifier);
		}
		if (parseBoolean(dependency, "optional", false)) {
			label.append(" optional=true");
		}
		return label.toString();
	}

	private static String formatPlugin(JSONObject plugin) {
		String groupId = first(plugin, "group_id", "groupId");
		String artifactId = first(plugin, "artifact_id", "artifactId");
		String version = first(plugin, "version");
		StringBuilder label = new StringBuilder();
		if (!groupId.isBlank()) {
			label.append(groupId).append(':');
		}
		label.append(artifactId.isBlank() ? "?" : artifactId);
		if (!version.isBlank()) {
			label.append(':').append(version);
		}
		String configuration = first(plugin, "configuration", "configuration_xml");
		if (!configuration.isBlank()) {
			label.append(" configuration=").append(singleLine(configuration));
		}
		if (plugin.has("executions") && !plugin.isNull("executions")) {
			JSONArray executions = plugin.getJSONArray("executions");
			for (int i = 0; i < executions.length(); i++) {
				JSONObject execution = executions.getJSONObject(i);
				label.append(" execution");
				String id = first(execution, "id");
				if (!id.isBlank()) {
					label.append("[id=").append(id).append(']');
				}
				String phase = first(execution, "phase");
				if (!phase.isBlank()) {
					label.append("[phase=").append(phase).append(']');
				}
				if (execution.has("goals") && !execution.isNull("goals")) {
					JSONArray goals = execution.getJSONArray("goals");
					List<String> goalNames = new ArrayList<>();
					for (int j = 0; j < goals.length(); j++) {
						goalNames.add(String.valueOf(goals.get(j)));
					}
					if (!goalNames.isEmpty()) {
						label.append("[goals=").append(String.join(",", goalNames)).append(']');
					}
				}
				String executionConfig = first(execution, "configuration", "configuration_xml");
				if (!executionConfig.isBlank()) {
					label.append(" config=").append(singleLine(executionConfig));
				}
			}
		}
		return label.toString();
	}

	private static List<String> readGoals(JSONObject arguments) {
		List<String> goals = new ArrayList<>();
		if (arguments.has("goals") && !arguments.isNull("goals")) {
			Object rawGoals = arguments.get("goals");
			if (rawGoals instanceof JSONArray array) {
				for (int i = 0; i < array.length(); i++) {
					String goal = String.valueOf(array.get(i)).trim();
					if (!goal.isBlank()) {
						goals.add(goal);
					}
				}
			} else {
				for (String goal : String.valueOf(rawGoals).split("\\s+")) {
					if (!goal.isBlank()) {
						goals.add(goal.trim());
					}
				}
			}
		}
		if (goals.isEmpty()) {
			String goal = first(arguments, "goal");
			if (!goal.isBlank()) {
				goals.add(goal);
			}
		}
		return goals;
	}

	private static void appendOptionalLine(StringBuilder description, String label, String value) {
		if (value != null && !value.isBlank()) {
			description.append('\n').append("  ").append(label).append(": ").append(value.trim());
		}
	}

	private static String formatPomResultSummary(String result) {
		StringBuilder summary = new StringBuilder();
		String firstLine = result.lines().findFirst().orElse("").trim();
		if (!firstLine.isBlank()) {
			summary.append("  ✓ ").append(firstLine);
		}
		for (String line : result.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.equals(firstLine)) {
				continue;
			}
			if (trimmed.startsWith("pom:")
					|| trimmed.startsWith("coordinates:")
					|| trimmed.startsWith("name:")
					|| trimmed.startsWith("description:")
					|| trimmed.startsWith("properties:")
					|| trimmed.startsWith("dependencies:")
					|| trimmed.startsWith("plugins:")
					|| trimmed.startsWith("- ")
					|| trimmed.startsWith("  ")) {
				summary.append('\n').append("  ").append(trimmed);
			}
		}
		return summary.toString().trim();
	}

	private static String formatBuildResultSummary(String result) {
		String firstLine = result.lines().findFirst().orElse("").trim();
		StringBuilder summary = new StringBuilder("  ");
		if (firstLine.contains("(exit 0)")) {
			summary.append("✓ ").append(firstLine);
		} else if (firstLine.contains("(exit ")) {
			summary.append("✗ ").append(firstLine);
		} else {
			summary.append("✓ ").append(firstLine);
		}
		String stderr = extractSection(result, "stderr:\n", null);
		String errLine = stderr.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
		if (!errLine.isBlank()) {
			summary.append('\n').append("  ! ").append(singleLine(errLine));
		}
		return summary.toString();
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

	private static String singleLine(String text) {
		return text.replace('\n', ' ').replace('\r', ' ').trim();
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
