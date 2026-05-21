package com.elowbe.main;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import com.elowbe.agent.AgentRunner;
import com.elowbe.commands.Command;
import com.elowbe.tools.AgentTools;
import com.jinteractive.gui.Settings;
import com.jinteractive.main.Colors;

import lib.console.main.JinCanvas;
import lib.console.main.JinConsole;
import lib.console.main.JinGraphics;
import lib.console.util.OllamaAPI;
import lib.console.widgets.InputWidget;
import lib.console.widgets.OptionsWidget;

public class ElowbeAgent extends JinCanvas {
	private static final File SYSTEM_PROMPT_FILE = resolveSystemPromptFile();

	/** Ollama model used for non-slash agent instructions. */
	private static String agentModel = "qwen3.6";
	private static String ollamaUrl = "http://10.0.0.8:11434";
	InputWidget commandInput;
	PrintWidget printWidget;
	File directory = new File("agenttest");
	private String systemPrompt = "";
	/** Primary skill.md supplement loaded for the current working directory. */
	private Skill primarySkill = Skill.parse("", null);
	/** Additional skills discovered under .cursor/skills/ or skills/. */
	private final List<Skill> discoveredSkills = new ArrayList<>();
	private final JSONArray chatHistory = new JSONArray();
	private volatile boolean agentBusy;
	/** Total input + output tokens consumed by the active or latest agent run. */
	public volatile long agentTokenCount;
	/** Total input + output tokens consumed across all agent runs in this session. */
	public volatile long totalAgentTokenCount;
	private long tokensAtRunStart;
	private long runTokenTotal;
	private long currentStreamTokenTotal;
	private volatile boolean subtaskActive;
	private final AtomicBoolean agentCancelRequested = new AtomicBoolean();
	private volatile Thread agentThread;
	private OptionsWidget modelPickerWidget;
	private boolean controlHeld;
	private boolean spaceHeld;
	/** Photos dropped onto the window, sent with the next agent message. */
	private final List<File> attachedPhotos = new ArrayList<>();

	public static void main(String[] args) {
		// Makes a window with 1280x720 pixel resolution and a console of 40 columns and
		// 30 rows.
		OllamaAPI.BASE_URL = ollamaUrl;
		Runtime.getRuntime().addShutdownHook(new Thread(AgentTools::cancelActiveProcesses, "elowbe-agent-shutdown"));
		JinConsole.start(new ElowbeAgent(), "Agent", 90, 40, 960, 720);
	}

	public void init() {
		printWidget = new PrintWidget(1, 1, JinConsole.getColumns() - 2, JinConsole.getRows() - 4);
		addWidget(printWidget);

		// Create widgets here.
		commandInput = new InputWidget("", 0, 0, JinConsole.getColumns(), 1);
		commandInput.elevation = 1;
		commandInput.commandHistoryEnabled = true;
		commandInput.onNewLine = () -> {

			runCommand(commandInput.value);
		};
		addWidget(commandInput);
		commandInput.takeFocus();

		loadSystemPrompt();
		loadSkills();
	}

	private static File resolveSystemPromptFile() {
		File[] candidates = { new File("src/system.txt"), new File("system.txt") };
		for (File candidate : candidates) {
			if (candidate.isFile()) {
				return candidate;
			}
		}
		return candidates[0];
	}

	private void loadSystemPrompt() {
		if (!SYSTEM_PROMPT_FILE.isFile()) {
			systemPrompt = "";
			return;
		}
		try {
			systemPrompt = Files.readString(SYSTEM_PROMPT_FILE.toPath(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			printWidget.println("Failed to load system prompt: " + e.getMessage());
		}
	}

	private void saveSystemPrompt() throws IOException {
		File parent = SYSTEM_PROMPT_FILE.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.writeString(SYSTEM_PROMPT_FILE.toPath(), systemPrompt, StandardCharsets.UTF_8);
	}

	private void loadSkills() {
		discoveredSkills.clear();
		primarySkill = Skill.parse("", null);

		File primaryFile = Skill.resolvePrimarySkillFile(directory);
		if (primaryFile.isFile()) {
			try {
				primarySkill = Skill.parse(primaryFile);
			} catch (IOException e) {
				printWidget.println("Failed to load skill file: " + e.getMessage());
			}
		}

		Map<String, Skill> byPath = new LinkedHashMap<>();
		for (File skillsRoot : Skill.skillDirectoryRoots(directory)) {
			for (Skill skill : Skill.discoverInDirectory(skillsRoot)) {
				byPath.put(skill.displayPath(), skill);
			}
		}
		if (primarySkill.sourceFile != null && primarySkill.sourceFile.isFile()) {
			byPath.remove(primarySkill.displayPath());
		}
		discoveredSkills.addAll(byPath.values());
	}

	private JSONObject buildUserMessage(String instruction, JSONArray images) {
		JSONObject message = new JSONObject()
				.put("role", "user")
				.put("content", buildProjectAwareInstruction(instruction));
		if (images != null && images.length() > 0) {
			message.put("images", images);
		}
		return message;
	}

	private String buildProjectAwareInstruction(String instruction) {
		ProjectProfile profile = ProjectProfile.from(instruction, directory);
		StringBuilder content = new StringBuilder();
		content.append("Runtime project preflight from ElowbeAgent:\n");
		content.append("- Current directory: ").append(profile.currentDirectoryPath()).append('\n');
		content.append("- Java and Maven are the default stack unless the user explicitly requested another language, build tool, or framework.\n");
		content.append("- Application type guidance: ").append(profile.frameworkGuidance).append('\n');
		content.append("- HARD COMPLETION RULE: before finishing, ensure run.sh and run.bat exist in the current directory, ");
		content.append("run the program with the script for the current OS, and include a run output analysis with key stdout/stderr findings.\n");
		if (profile.mavenProjectRoot == null) {
			content.append("- Maven project check: no pom.xml was found in the current directory or any parent directory.\n");
			if (profile.usesJavaMavenDefault) {
				content.append("- REQUIRED FIRST STEP: create a Maven project in the current directory before doing any feature work. ");
				content.append("Create pom.xml and the standard src/main/java and src/test/java layout, then continue the user's task inside that Maven project.\n");
			} else {
				content.append("- The user explicitly requested a non-default stack; follow that request instead of creating a Maven project.\n");
			}
		} else {
			content.append("- Maven project check: pom.xml found at ")
					.append(profile.mavenProjectRoot.getPath())
					.append(". Run Maven commands from this project root.\n");
		}
		content.append("\nUser request:\n").append(instruction);
		return content.toString();
	}

	private JSONArray buildChatRequest(JSONObject userMessage) {
		JSONArray request = new JSONArray();
		String resolvedSystemPrompt = buildResolvedSystemPrompt();
		if (!resolvedSystemPrompt.isBlank()) {
			JSONObject systemMessage = new JSONObject();
			systemMessage.put("role", "system");
			systemMessage.put("content", resolvedSystemPrompt);
			request.put(systemMessage);
		}
		for (int i = 0; i < chatHistory.length(); i++) {
			request.put(chatHistory.get(i));
		}
		request.put(userMessage);
		return request;
	}

	private String buildResolvedSystemPrompt() {
		StringBuilder resolved = new StringBuilder();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			resolved.append(systemPrompt);
		}
		appendSkillSupplement(resolved);
		if (resolved.length() > 0) {
			resolved.append("\n\n");
		}
		resolved.append("Host operating system: ").append(detectOperatingSystem());
		return resolved.toString();
	}

	private void appendSkillSupplement(StringBuilder resolved) {
		if (primarySkill.hasBody()) {
			if (resolved.length() > 0) {
				resolved.append("\n\n");
			}
			resolved.append("Skill supplement (").append(primarySkill.name).append("):\n");
			resolved.append(primarySkill.body);
		}

		if (discoveredSkills.isEmpty()) {
			return;
		}

		if (resolved.length() > 0) {
			resolved.append("\n\n");
		}
		resolved.append("Available agent skills:\n");
		resolved.append("When a user task matches a skill description, read that skill's file and follow its instructions.\n");
		for (Skill skill : discoveredSkills) {
			resolved.append(Skill.formatCatalogEntry(skill)).append('\n');
		}
	}

	private String detectOperatingSystem() {
		String osName = System.getProperty("os.name", "unknown");
		String osVersion = System.getProperty("os.version", "unknown");
		String osArch = System.getProperty("os.arch", "unknown");
		return osName + " " + osVersion + " (" + osArch + ")";
	}

	public void runCommand(String line) {
		line = line == null ? "" : line.trim();
		if (line.isEmpty() && attachedPhotos.isEmpty()) {
			return;
		}
		commandInput.addCommandToHistory(line);
		printWidget.setColor(Colors.white);

		String displayLine = line.isEmpty() ? "(photo attachment)" : line;
		if (!attachedPhotos.isEmpty()) {
			displayLine += " [" + attachedPhotos.size() + " photo(s)]";
		}
		printWidget.println("> " + displayLine);
		printWidget.setColor(Colors.white);
		commandInput.setValue("");

		if (line.startsWith("/")) {
			runSlashCommand(line.substring(1).trim());
			return;
		}
		if (runTerminalCommand(line)) {
			return;
		}

		List<File> photosToSend = takeAttachedPhotos();
		JSONArray images;
		try {
			images = encodePhotos(photosToSend);
		} catch (IOException e) {
			printWidget.println("Failed to read attached photo: " + e.getMessage());
			attachedPhotos.addAll(photosToSend);
			return;
		}

		printWidget.setColor(Colors.blue);
		String instruction = line;
		if (instruction.isEmpty() && images.length() > 0) {
			instruction = "Please analyze the attached image(s).";
		}
		sendAgentInstruction(instruction, images);
	}

	private boolean runTerminalCommand(String line) {
		int space = line.indexOf(' ');
		String name = space < 0 ? line : line.substring(0, space);
		String args = space < 0 ? "" : line.substring(space + 1).trim();

		return switch (name) {
		case "cd" -> {
			runCd(args);
			yield true;
		}
		case "ls" -> {
			runLs(args);
			yield true;
		}
		case "mkdir" -> {
			runMkdir(args);
			yield true;
		}
		default -> false;
		};
	}

	private void runCd(String path) {
		if (path.isEmpty() || path.equals("~")) {
			path = System.getProperty("user.home");
		} else if (path.startsWith("~/")) {
			path = System.getProperty("user.home") + path.substring(1);
		} else {
			path = stripQuotes(path);
		}

		File target = new File(path);
		if (!target.isAbsolute()) {
			target = new File(directory, path);
		}
		try {
			target = target.getCanonicalFile();
		} catch (IOException e) {
			printWidget.println("cd: " + e.getMessage());
			return;
		}
		if (!target.isDirectory()) {
			printWidget.println("cd: no such directory: " + path);
			return;
		}
		directory = target;
		loadSkills();
	}

	private void runLs(String path) {
		File dir = path.isEmpty() ? directory : resolvePath(path);
		if (!dir.exists()) {
			printWidget.println("ls: cannot access '" + path + "': No such file or directory");
			return;
		}
		if (!dir.isDirectory()) {
			printWidget.println(dir.getName());
			return;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			printWidget.println("ls: cannot access '" + dir.getPath() + "'");
			return;
		}
		java.util.Arrays.sort(files, (a, b) -> {
			if (a.isDirectory() != b.isDirectory()) {
				return a.isDirectory() ? -1 : 1;
			}
			return a.getName().compareToIgnoreCase(b.getName());
		});
		for (File file : files) {
			printWidget.println(file.isDirectory() ? file.getName() + "/" : file.getName());
		}
	}

	private void runMkdir(String path) {
		boolean parents = false;
		if (path.startsWith("-p")) {
			parents = true;
			path = path.length() == 2 ? "" : path.substring(2).trim();
		}
		if (path.isEmpty()) {
			printWidget.println("mkdir: missing operand");
			return;
		}
		File target = resolvePath(path);
		if (target.exists()) {
			printWidget.println("mkdir: cannot create directory '" + path + "': File exists");
			return;
		}
		boolean ok = parents ? target.mkdirs() : target.mkdir();
		if (!ok) {
			printWidget.println("mkdir: cannot create directory '" + path + "'");
		}
	}

	private File resolvePath(String path) {
		path = stripQuotes(path.trim());
		File target = new File(path);
		if (!target.isAbsolute()) {
			target = new File(directory, path);
		}
		return target;
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
				|| (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private void runSlashCommand(String commandLine) {
		if (commandLine.isEmpty()) {
			printWidget.println("Error: missing command after /");
			return;
		}

		Command cmd;
		try {
			cmd = Command.parse(commandLine);
		} catch (IllegalArgumentException e) {
			printWidget.println("Error: " + e.getMessage());
			return;
		}

		handleCommand(cmd);
	}

	private void sendAgentInstruction(String instruction) {
		sendAgentInstruction(instruction, new JSONArray());
	}

	private void sendAgentInstruction(String instruction, JSONArray images) {
		if (agentBusy) {
			printWidget.println("Agent is still responding. Please wait.");
			return;
		}

		JSONObject userMessage = buildUserMessage(instruction, images);

		JSONArray request = buildChatRequest(userMessage);
		int turnStart = request.length() - 1;

		agentTokenCount = 0;
		tokensAtRunStart = totalAgentTokenCount;
		runTokenTotal = 0;
		currentStreamTokenTotal = 0;
		subtaskActive = false;
		agentBusy = true;
		agentCancelRequested.set(false);

		Thread thread = new Thread(() -> {
			String previousModel = OllamaAPI.model;
			OllamaAPI.model = agentModel;
			try {
				printWidget.print("<#green>");
				printWidget.setColor(Colors.green);
				AgentRunner.run(request, directory, token -> {
					//printWidget.setColor(Colors.green);
					//printWidget.print(token);
				}, thinking -> {
					printWidget.setColor(subtaskActive ? Colors.darkblue : Colors.lightgray);
					printWidget.print(thinking);
				}, (name, args, result) -> logToolActivity(name, args, result), this::addAgentTokenUsage,
						() -> agentCancelRequested.get() || Thread.currentThread().isInterrupted());

				if (!agentCancelRequested.get()) {
					for (int i = turnStart; i < request.length(); i++) {
						chatHistory.put(request.get(i));
					}
					printWidget.println();
				}
			} catch (IOException e) {
				if (agentCancelRequested.get() || Thread.currentThread().isInterrupted()) {
					printWidget.println();
					printWidget.println("Agent cancelled.");
				} else {
					printWidget.println("Agent error: " + e.getMessage());
				}
			} catch (Exception e) {
				if (agentCancelRequested.get() || Thread.currentThread().isInterrupted()) {
					printWidget.println();
					printWidget.println("Agent cancelled.");
				} else {
					printWidget.println("Agent error: " + e.getMessage());
				}
			} finally {
				OllamaAPI.model = previousModel;
				agentBusy = false;
				subtaskActive = false;
				agentCancelRequested.set(false);
				if (agentThread == Thread.currentThread()) {
					agentThread = null;
				}
			}
		}, "elowbe-agent-chat");
		agentThread = thread;
		thread.start();
	}

	private void logToolActivity(String name, JSONObject args, String result) {
		printWidget.println();
		boolean subtaskEvent = name.startsWith("subtask.");
		if ("subtask.start".equals(name)) {
			subtaskActive = true;
			printWidget.print("<#lightblue>");
			printWidget.setColor(Colors.lightblue);
			printWidget.println(AgentRunner.formatToolAction(name, args));
		} else if ("subtask.finish".equals(name)) {
			printWidget.setColor(Colors.lightblue);
			printWidget.println(AgentRunner.formatToolAction(name, args));
			String summary = AgentRunner.formatToolResultSummary(name, result);
			if (!summary.isBlank()) {
				printWidget.setColor(Colors.lightgray);
				printWidget.println(summary);
			}
			subtaskActive = false;
		} else if (subtaskEvent) {
			printWidget.print("<#lightblue>");
			printWidget.setColor(Colors.lightblue);
			printWidget.println(AgentRunner.formatToolAction(name, args));
			String summary = AgentRunner.formatToolResultSummary(name, result);
			if (!summary.isBlank()) {
				printWidget.setColor(Colors.lightgray);
				for (String line : summary.split("\n", -1)) {
					printWidget.println(line);
				}
			}
		} else {
			printWidget.print("<#yellow>");
			printWidget.setColor(Colors.yellow);
			printWidget.println(AgentRunner.formatToolAction(name, args));
			String summary = AgentRunner.formatToolResultSummary(name, result);
			if (!summary.isBlank()) {
				printWidget.setColor(Colors.lightgray);
				for (String line : summary.split("\n", -1)) {
					printWidget.println(line);
				}
			}
		}
		printWidget.print("<#green>");
		printWidget.setColor(Colors.green);
	}

	private synchronized void addAgentTokenUsage(JSONObject usage) {
		if (usage == null) {
			return;
		}
		boolean streaming = usage.optBoolean("__streaming", false);
		long totalTokens = usage.optLong("total_tokens",
				usage.optLong("prompt_tokens", 0) + usage.optLong("completion_tokens", 0));
		if (streaming) {
			currentStreamTokenTotal = totalTokens;
		} else {
			runTokenTotal += totalTokens;
			currentStreamTokenTotal = 0;
		}
		agentTokenCount = runTokenTotal + currentStreamTokenTotal;
		totalAgentTokenCount = tokensAtRunStart + agentTokenCount;
	}

	private boolean cancelAgent() {
		if (!agentBusy) {
			return false;
		}
		if (!agentCancelRequested.compareAndSet(false, true)) {
			return true;
		}
		printWidget.println();
		printWidget.println("Cancelling agent...");
		AgentTools.cancelActiveProcesses();
		Thread thread = agentThread;
		if (thread != null) {
			thread.interrupt();
		}
		return true;
	}

	public String getAgentModel() {
		return agentModel;
	}

	public void setAgentModel(String agentModel) {
		if (agentModel != null && !agentModel.isBlank()) {
			this.agentModel = agentModel.trim();
		}
	}

	private void handleCommand(Command cmd) {
		switch (cmd.getName()) {
		case "help" -> printHelp(cmd);
		case "clear" -> {
			chatHistory.clear();
			printWidget.clear();
			agentTokenCount = 0;
			totalAgentTokenCount = 0;
			tokensAtRunStart = 0;
			runTokenTotal = 0;
			currentStreamTokenTotal = 0;
		}
		case "model" -> openModelPicker();
		case "system" -> handleSystemCommand(cmd);
		case "skill" -> handleSkillCommand(cmd);
		default -> printWidget.println("Unknown command: " + cmd.getName());
		}
	}

	private void openModelPicker() {
		if (modelPickerWidget != null && !modelPickerWidget.isDestroyed()) {
			modelPickerWidget.takeFocus();
			return;
		}

		printWidget.println("Loading models...");

		new Thread(() -> {
			try {
				ArrayList<Object[]> models = OllamaAPI.listModels();
				showModelPicker(models);
			} catch (IOException e) {
				printWidget.println("Failed to load models: " + e.getMessage());
			} catch (Exception e) {
				printWidget.println("Failed to load models: " + e.getMessage());
			}
		}, "elowbe-model-list").start();
	}

	private void showModelPicker(ArrayList<Object[]> models) {
		if (models.isEmpty()) {
			printWidget.println("No models installed.");
			return;
		}

		String[] modelNames = new String[models.size()];
		for (int i = 0; i < models.size(); i++) {
			modelNames[i] = models.get(i)[0].toString();
		}

		int panelWidth = JinConsole.getColumns() - 4;
		int panelHeight = JinConsole.getRows() - 6;
		int column = (JinConsole.getColumns() - panelWidth) / 2;
		int row = (JinConsole.getRows() - panelHeight) / 2;

		OptionsWidget options = new OptionsWidget(column, row, modelNames);
		options.panelWidth = panelWidth;
		options.panelHeight = panelHeight;
		options.title = "Select Model";
		options.elevation = 100;
		options.color = Colors.white;

		for (int i = 0; i < modelNames.length; i++) {
			if (agentModel.equals(modelNames[i])) {
				options.setSelectedIndex(i);
				break;
			}
		}

		options.setCallback((index, modelName) -> {
			setAgentModel(modelName);
			printWidget.println("Model set to " + agentModel);
			closeModelPicker();
		});
		options.onCancel = () -> {
			printWidget.println("Model selection cancelled.");
			closeModelPicker();
		};

		modelPickerWidget = options;
		addWidget(options);
		options.takeFocus();
	}

	private void handleSkillCommand(Command cmd) {
		if (cmd.has("reload")) {
			loadSkills();
			printWidget.println("Skills reloaded for " + directory.getPath());
			printPrimarySkillStatus();
			printDiscoveredSkillStatus();
			return;
		}
		if (cmd.has("show")) {
			if (!primarySkill.hasBody()) {
				printWidget.println("(no primary skill.md loaded)");
			} else {
				printWidget.println("Primary skill: " + primarySkill.name);
				if (primarySkill.sourceFile != null) {
					printWidget.println("File: " + primarySkill.sourceFile.getPath());
				}
				if (primarySkill.hasDescription()) {
					printWidget.println("Description: " + primarySkill.description);
				}
				printWidget.println(primarySkill.body);
			}
			printDiscoveredSkillStatus();
			return;
		}
		if (cmd.has("list")) {
			printPrimarySkillStatus();
			printDiscoveredSkillStatus();
			return;
		}
		printWidget.println("Primary skill file: " + Skill.resolvePrimarySkillFile(directory).getPath());
		printPrimarySkillStatus();
		printDiscoveredSkillStatus();
		printWidget.println("  /skill -show              show loaded skill content");
		printWidget.println("  /skill -list              list primary and discovered skills");
		printWidget.println("  /skill -reload            reload from disk");
		printWidget.println("Skill directories: .cursor/skills/, skills/");
	}

	private void printPrimarySkillStatus() {
		if (!primarySkill.hasBody()) {
			printWidget.println("Primary skill: (none)");
			return;
		}
		String path = primarySkill.sourceFile == null ? "unknown" : primarySkill.sourceFile.getPath();
		printWidget.println("Primary skill: " + primarySkill.name + " (" + path + ", " + primarySkill.body.length()
				+ " chars)");
	}

	private void printDiscoveredSkillStatus() {
		if (discoveredSkills.isEmpty()) {
			printWidget.println("Discovered skills: (none)");
			printWidget.println("Searched skill roots:");
			for (File root : Skill.skillDirectoryRoots(directory)) {
				printWidget.println("  " + root.getPath());
			}
			return;
		}
		printWidget.println("Discovered skills:");
		for (Skill skill : discoveredSkills) {
			printWidget.println("  " + Skill.formatCatalogEntry(skill));
		}
	}

	private void handleSystemCommand(Command cmd) {
		if (cmd.has("reload")) {
			loadSystemPrompt();
			printWidget.println("System prompt reloaded from " + SYSTEM_PROMPT_FILE.getPath() + " ("
					+ systemPrompt.length() + " chars)");
			return;
		}
		if (cmd.has("set")) {
			String value = cmd.get("set");
			if (value == null || value.isBlank()) {
				printWidget.println("Error: -set requires a value");
				return;
			}
			systemPrompt = value.trim();
			try {
				saveSystemPrompt();
				printWidget.println("System prompt saved to " + SYSTEM_PROMPT_FILE.getPath());
			} catch (IOException e) {
				printWidget.println("System prompt updated in memory, but save failed: " + e.getMessage());
			}
			return;
		}
		if (cmd.has("show")) {
			if (systemPrompt.isBlank()) {
				printWidget.println("(empty system prompt)");
			} else {
				printWidget.println(systemPrompt);
			}
			return;
		}
		printWidget.println("System prompt file: " + SYSTEM_PROMPT_FILE.getAbsolutePath());
		printWidget.println("Length: " + systemPrompt.length() + " chars");
		printWidget.println("  /system -show              show current prompt");
		printWidget.println("  /system -reload            reload from file");
		printWidget.println("  /system -set \"...\"         set and save prompt");
	}

	private void closeModelPicker() {
		if (modelPickerWidget == null) {
			return;
		}
		modelPickerWidget.blur();
		removeWidget(modelPickerWidget);
		modelPickerWidget = null;
		commandInput.takeFocus();
	}

	private void printHelp(Command cmd) {
		if (cmd.has("commands")) {
			printWidget.println("/help -commands    show this list");
			printWidget.println("/clear             clear chat history");
			printWidget.println("/model             choose Ollama model");
			printWidget.println("/system            system prompt (" + SYSTEM_PROMPT_FILE.getPath() + ")");
			printWidget.println("/skill             skill.md supplement and .cursor/skills/");
			printWidget.println("Ctrl+Shift+C      cancel the running agent and its process");
			printWidget.println("Drag and drop      attach photos to the next agent message");
			printWidget.println("cd [path]          change directory (~ for home)");
			printWidget.println("ls [path]          list directory contents");
			printWidget.println("mkdir [-p] dir     create directory");
			printWidget.println("plain text         send to agent (" + agentModel + ")");
			return;
		}
		printWidget.println("Slash commands: /name -opt value -flag");
		printWidget.println("Terminal: cd, ls, mkdir. Other input goes to the agent.");
		printWidget.println("  /help -commands");
		printWidget.println("  /model");
		printWidget.println("  /system -show");
			printWidget.println("  /skill -list");
	}

	@Override
	public boolean onFilesDropped(List<File> files) {
		if (files == null || files.isEmpty()) {
			return false;
		}
		int added = 0;
		for (File file : files) {
			if (isImageFile(file)) {
				attachedPhotos.add(file);
				added++;
			}
		}
		if (added == 0) {
			printWidget.println("Drop ignored: supported image types are .jpg, .jpeg, .png, .gif, .webp, .bmp");
			return false;
		}
		printWidget.println("Attached " + added + " photo(s). Press Enter to send with your message.");
		commandInput.takeFocus();
		return true;
	}

	private static boolean isImageFile(File file) {
		if (file == null || !file.isFile()) {
			return false;
		}
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
				|| name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
	}

	private List<File> takeAttachedPhotos() {
		List<File> photos = new ArrayList<>(attachedPhotos);
		attachedPhotos.clear();
		return photos;
	}

	private JSONArray encodePhotos(List<File> photos) throws IOException {
		JSONArray images = new JSONArray();
		for (File photo : photos) {
			images.put(OllamaAPI.encodeImageToBase64(photo.getAbsolutePath()));
		}
		return images;
	}

	public void tick(float delta) {
		// Change properties and do work here
	}

	public static String truncatePath(String path) {
		if (path == null || path.isBlank()) {
			return path;
		}

		// Normalize separators
		path = path.replace("\\", "/");

		// Remove trailing slash
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}

		String[] parts = path.split("/");

		if (parts.length <= 2) {
			return path;
		}

		return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
	}

	public void draw(JinGraphics t2d) {
		// Draw here. Anything drawn here will display over all Widgets
		// Top-Left corner is the origin.

//		String text = "<#green>Hello Jin Console";
//		String clean = ColorExtractor.cleanString(text); // removes color tags for correct length
//		t2d.drawString(clean, JinConsole.getColumns() / 2 - clean.length() /2, JinConsole.getRows() / 2 - 2);

		commandInput.row = JinConsole.getRows() - commandInput.height + 1;
		commandInput.column = 0;
		commandInput.height = Math.max(3, commandInput.getLines() + 3);
		if (!agentBusy) {
			t2d.setColor(Colors.gray);
		} else {
			t2d.setColor(Colors.darkgreen);

		}
		t2d.drawBox(0, commandInput.row, JinConsole.getColumns(), commandInput.height - 1);

		if (!agentBusy) {
			t2d.setColor(Colors.lightgray);
		} else {
			t2d.setColor(Colors.green);

		}
		t2d.drawBox(0, 0, JinConsole.getColumns(), JinConsole.getRows());
		t2d.setColor(Colors.gray);

		t2d.drawString(" " + truncatePath(directory.getAbsolutePath()) + " ", 2,
				commandInput.row + 2 + commandInput.height - 4);
		if (!attachedPhotos.isEmpty()) {
			t2d.setColor(Colors.lightblue);
			t2d.drawString(" " + attachedPhotos.size() + " photo(s) ", JinConsole.getColumns() - 14,
					commandInput.row + 2 + commandInput.height - 4);
		}
		String tokenMeter;
		if (agentBusy) {
			tokenMeter = agentModel + " [" + agentTokenCount + " run | " + totalAgentTokenCount + " session : "
					+ tokenCost(totalAgentTokenCount) + "]";
		} else {
			tokenMeter = agentModel + " [" + totalAgentTokenCount + " tokens : " + tokenCost(totalAgentTokenCount) + "]";
		}
		t2d.drawString(" " + tokenMeter + " ", 2, 0);
	}
	
	public String tokenCost(float agentTokenCount) {
		return String.format("$%.2f", ((agentTokenCount/1000000f)* 25f));
	}

	public void destroy() {
		cancelAgent();
		AgentTools.cancelActiveProcesses();
	}

	public void scroll(int amount) {
		// Scroll wheel handler
	}

	public void keyDown(KeyEvent e) {
		updateHeldKeys(e, true);
		if (isControlDown(e) ) {
			if (e.getKeyCode() == KeyEvent.VK_UP) {
				printWidget.scroll(e.isShiftDown() ? -5 : -1);
				e.consume();
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_DOWN) {
				printWidget.scroll(e.isShiftDown() ? 5 : 1);
				e.consume();
				return;
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_C && e.isControlDown() && e.isShiftDown() && cancelAgent()) {
			e.consume();
		}
	}

	public void keyUp(KeyEvent e) {
		updateHeldKeys(e, false);

	}
	public boolean isControlDown(KeyEvent e) {
		return Settings.isMac() ? e.isMetaDown() : e.isControlDown();
	}
	private void updateHeldKeys(KeyEvent e, boolean pressed) {
		if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
			controlHeld = pressed;
		} else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
			spaceHeld = pressed;
		}
	}

	private static class ProjectProfile {
		private final File currentDirectory;
		private final File mavenProjectRoot;
		private final boolean usesJavaMavenDefault;
		private final String frameworkGuidance;

		private ProjectProfile(File currentDirectory, File mavenProjectRoot, boolean usesJavaMavenDefault,
				String frameworkGuidance) {
			this.currentDirectory = currentDirectory;
			this.mavenProjectRoot = mavenProjectRoot;
			this.usesJavaMavenDefault = usesJavaMavenDefault;
			this.frameworkGuidance = frameworkGuidance;
		}

		private static ProjectProfile from(String instruction, File directory) {
			File current = directory == null ? new File(System.getProperty("user.dir")) : directory;
			File mavenRoot = findMavenProjectRoot(current);
			boolean javaMavenDefault = !explicitlyRequestsAnotherStack(instruction);
			return new ProjectProfile(current, mavenRoot, javaMavenDefault, frameworkGuidance(instruction));
		}

		private String currentDirectoryPath() {
			try {
				return currentDirectory.getCanonicalPath();
			} catch (IOException e) {
				return currentDirectory.getAbsolutePath();
			}
		}

		private static File findMavenProjectRoot(File start) {
			File current;
			try {
				current = start.getCanonicalFile();
			} catch (IOException e) {
				current = start.getAbsoluteFile();
			}
			if (current.isFile()) {
				current = current.getParentFile();
			}
			while (current != null) {
				File pom = new File(current, "pom.xml");
				if (pom.isFile()) {
					return current;
				}
				current = current.getParentFile();
			}
			return null;
		}

		private static String frameworkGuidance(String instruction) {
			String text = normalize(instruction);
			if (mentionsAny(text, "web app", "webapp", "website", "rest api", "http api", "backend", "server")) {
				return "use Spring Boot with Maven for web applications unless the user explicitly chose another stack.";
			}
			if (mentionsAny(text, "gui", "desktop app", "desktop application", "windowed app", "native app")) {
				return "use Java Swing with Maven for GUI or desktop applications unless the user explicitly chose another stack.";
			}
			if (mentionsAny(text, "cli", "command line", "terminal app", "console app")) {
				return "use a Java Maven CLI structure unless the user explicitly chose another stack.";
			}
			return "use a Java Maven project structure by default.";
		}

		private static boolean explicitlyRequestsAnotherStack(String instruction) {
			String text = normalize(instruction);
			return mentionsAny(text,
					"python", "pip", "django", "flask", "fastapi",
					"javascript", "typescript", "node", "node.js", "npm", "pnpm", "yarn", "react", "vue", "angular",
					"gradle", "kotlin", "scala", "groovy",
					"rust", "cargo", "go ", "golang", "c#", ".net", "dotnet",
					"ruby", "rails", "php", "laravel", "swift");
		}

		private static String normalize(String value) {
			return value == null ? "" : value.toLowerCase(Locale.ROOT);
		}

		private static boolean mentionsAny(String text, String... terms) {
			for (String term : terms) {
				if (text.contains(term)) {
					return true;
				}
			}
			return false;
		}
	}

	@Override
	public void render(Graphics2D g2d) {
		// TODO Auto-generated method stub
		g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
	}
}