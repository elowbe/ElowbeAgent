package com.elowbe.main;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import com.elowbe.agent.AgentRunner;
import com.elowbe.commands.Command;
import com.elowbe.tools.AgentTools;
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
	private static String agentModel = "qwen3.5:9b";
	private static String ollamaUrl = "http://10.0.0.23:11434";
	InputWidget commandInput;
	PrintWidget printWidget;
	File directory = new File("agenttest");
	private String systemPrompt = "";
	private final JSONArray chatHistory = new JSONArray();
	private volatile boolean agentBusy;
	private final AtomicBoolean agentCancelRequested = new AtomicBoolean();
	private volatile Thread agentThread;
	private OptionsWidget modelPickerWidget;
	private boolean controlHeld;
	private boolean spaceHeld;

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
		commandInput.onNewLine = () -> {

			runCommand(commandInput.value);
		};
		addWidget(commandInput);
		commandInput.takeFocus();

		loadSystemPrompt();
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

	private JSONArray buildChatRequest(JSONObject userMessage) {
		JSONArray request = new JSONArray();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			JSONObject systemMessage = new JSONObject();
			systemMessage.put("role", "system");
			systemMessage.put("content", systemPrompt);
			request.put(systemMessage);
		}
		for (int i = 0; i < chatHistory.length(); i++) {
			request.put(chatHistory.get(i));
		}
		request.put(userMessage);
		return request;
	}

	public void runCommand(String line) {
		line = line == null ? "" : line.trim();
		if (line.isEmpty()) {
			return;
		}
		printWidget.setColor(Colors.lightgray);

		printWidget.println("> " + line);
		printWidget.setColor(Colors.white);
		commandInput.setValue("");

		if (line.startsWith("/")) {
			runSlashCommand(line.substring(1).trim());
		} else if (!runTerminalCommand(line)) {
			sendAgentInstruction(line);
		}
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
		if (agentBusy) {
			printWidget.println("Agent is still responding. Please wait.");
			return;
		}

		JSONObject userMessage = new JSONObject();
		userMessage.put("role", "user");
		userMessage.put("content", instruction);

		JSONArray request = buildChatRequest(userMessage);
		int turnStart = request.length() - 1;

		agentBusy = true;
		agentCancelRequested.set(false);

		Thread thread = new Thread(() -> {
			String previousModel = OllamaAPI.model;
			OllamaAPI.model = agentModel;
			try {
				printWidget.print("<#green>");
				printWidget.setColor(Colors.green);
				AgentRunner.run(request, directory, token -> {
					printWidget.setColor(Colors.green);
					printWidget.print(token);
				}, thinking -> {
					printWidget.setColor(Colors.gray);
					printWidget.print(thinking);
				}, (name, args, result) -> {
					printWidget.println();
					printWidget.print("<#yellow>");
					printWidget.setColor(Colors.yellow);
					printWidget.println(AgentRunner.formatToolCall(name, args));
					printWidget.setColor(Colors.lightgray);
					for (String line : result.split("\n", -1)) {
						printWidget.println(line);
					}
					printWidget.print("<#green>");
					printWidget.setColor(Colors.green);
				}, () -> agentCancelRequested.get() || Thread.currentThread().isInterrupted());

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
				agentCancelRequested.set(false);
				if (agentThread == Thread.currentThread()) {
					agentThread = null;
				}
			}
		}, "elowbe-agent-chat");
		agentThread = thread;
		thread.start();
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
		}
		case "model" -> openModelPicker();
		case "system" -> handleSystemCommand(cmd);
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
			printWidget.println("Ctrl+Shift+C      cancel the running agent and its process");
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
		if (controlHeld && spaceHeld) {
			if (e.getKeyCode() == KeyEvent.VK_UP) {
				printWidget.scroll(-1);
				e.consume();
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_DOWN) {
				printWidget.scroll(1);
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

	private void updateHeldKeys(KeyEvent e, boolean pressed) {
		if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
			controlHeld = pressed;
		} else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
			spaceHeld = pressed;
		}
	}

	@Override
	public void render(Graphics2D g2d) {
		// TODO Auto-generated method stub
		g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
	}
}