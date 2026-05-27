package com.elowbe.main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent user settings for ElowbeAgent ({@code ~/.elowbe-agent/settings.json}).
 */
public final class AgentSettings {
	private static final File SETTINGS_DIR = new File(System.getProperty("user.home"), ".elowbe-agent");
	private static final File SETTINGS_FILE = new File(SETTINGS_DIR, "settings.json");

	private static final String DEFAULT_AGENT_MODEL = "lmstudio:qwen3.6-27b-mtp";
	private static final String DEFAULT_OLLAMA_URL = "http://10.0.0.8:11434";
	private static final String DEFAULT_LMSTUDIO_URL = "http://10.0.0.8:1234/v1";
	private static final String DEFAULT_AGENT_BROWSER_COMMAND = "agent-browser --json";
	private static final int DEFAULT_AGENT_MAX_OUTPUT_TOKENS = 32000;

	private File projectsDirectory;
	private String agentModel = DEFAULT_AGENT_MODEL;
	private String ollamaUrl = DEFAULT_OLLAMA_URL;
	private String lmstudioUrl = DEFAULT_LMSTUDIO_URL;
	private boolean thinkingEnabled = true;
	private boolean subtasksEnabled = false;
	private boolean agentBrowserWebEnabled = true;
	private String agentBrowserCommand = DEFAULT_AGENT_BROWSER_COMMAND;
	private int agentContextLength;
	private int agentMaxOutputTokens = DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
	private List<String> manualSkillIds = List.of();

	private AgentSettings(File projectsDirectory) {
		this.projectsDirectory = projectsDirectory;
	}

	public static File defaultProjectsDirectory() {
		return new File(System.getProperty("user.home"), "Documents/elowbe-projects");
	}

	public static AgentSettings load() {
		AgentSettings settings = new AgentSettings(defaultProjectsDirectory());
		if (!SETTINGS_FILE.isFile()) {
			return settings;
		}
		try {
			String raw = Files.readString(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8);
			JSONObject json = new JSONObject(raw);
			String path = json.optString("projectsDirectory", "");
			settings.projectsDirectory = path.isBlank() ? defaultProjectsDirectory()
					: new File(path).getAbsoluteFile();
			settings.agentModel = json.optString("agentModel", DEFAULT_AGENT_MODEL);
			settings.ollamaUrl = json.optString("ollamaUrl", DEFAULT_OLLAMA_URL);
			settings.lmstudioUrl = json.optString("lmstudioUrl", DEFAULT_LMSTUDIO_URL);
			settings.thinkingEnabled = json.optBoolean("thinkingEnabled", true);
			settings.subtasksEnabled = json.optBoolean("subtasksEnabled", false);
			settings.agentBrowserWebEnabled = json.optBoolean("agentBrowserWebEnabled", true);
			settings.agentBrowserCommand = json.optString("agentBrowserCommand", DEFAULT_AGENT_BROWSER_COMMAND);
			settings.agentContextLength = json.optInt("agentContextLength", 0);
			settings.agentMaxOutputTokens = json.optInt("agentMaxOutputTokens", DEFAULT_AGENT_MAX_OUTPUT_TOKENS);
			settings.manualSkillIds = parseSkillIds(json.optJSONArray("manualSkillIds"));
			return settings;
		} catch (Exception e) {
			return new AgentSettings(defaultProjectsDirectory());
		}
	}

	private static List<String> parseSkillIds(JSONArray array) {
		if (array == null || array.isEmpty()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			String id = array.optString(i, "").trim();
			if (!id.isEmpty()) {
				ids.add(id);
			}
		}
		return List.copyOf(ids);
	}

	public void applyTo(ElowbeAgent agent) {
		agent.setAgentModel(agentModel);
		agent.setOllamaUrl(ollamaUrl);
		agent.setLmstudioUrl(lmstudioUrl);
		agent.setThinkingEnabled(thinkingEnabled);
		agent.setSubtasksEnabled(subtasksEnabled);
		agent.setAgentBrowserWebEnabled(agentBrowserWebEnabled);
		agent.setAgentBrowserCommand(agentBrowserCommand);
		agent.setAgentContextLength(agentContextLength);
		agent.setAgentMaxOutputTokens(agentMaxOutputTokens);
		agent.setPersistedManualSkillIds(manualSkillIds);
	}

	public void captureFrom(ElowbeAgent agent) {
		agentModel = agent.getAgentModel();
		ollamaUrl = agent.getOllamaUrl();
		lmstudioUrl = agent.getLmstudioUrl();
		thinkingEnabled = agent.isThinkingEnabled();
		subtasksEnabled = agent.isSubtasksEnabled();
		agentBrowserWebEnabled = agent.isAgentBrowserWebEnabled();
		agentBrowserCommand = agent.getAgentBrowserCommand();
		agentContextLength = agent.getAgentContextLength();
		agentMaxOutputTokens = agent.getAgentMaxOutputTokens();
		manualSkillIds = List.copyOf(agent.getManualSkillIds());
	}

	public File getProjectsDirectory() {
		return projectsDirectory;
	}

	public void setProjectsDirectory(File projectsDirectory) {
		if (projectsDirectory == null) {
			throw new IllegalArgumentException("Projects directory cannot be null");
		}
		this.projectsDirectory = projectsDirectory.getAbsoluteFile();
	}

	public File ensureProjectsDirectory() throws IOException {
		if (!projectsDirectory.exists() && !projectsDirectory.mkdirs()) {
			throw new IOException("Could not create projects directory: " + projectsDirectory.getPath());
		}
		if (!projectsDirectory.isDirectory()) {
			throw new IOException("Projects path is not a directory: " + projectsDirectory.getPath());
		}
		return projectsDirectory;
	}

	public boolean isProjectsRoot(File directory) {
		if (directory == null) {
			return false;
		}
		try {
			return directory.getCanonicalFile().equals(projectsDirectory.getCanonicalFile());
		} catch (IOException e) {
			return directory.getAbsoluteFile().equals(projectsDirectory.getAbsoluteFile());
		}
	}

	public static boolean isProjectsRoot(File directory, File projectsDirectory) {
		if (directory == null || projectsDirectory == null) {
			return false;
		}
		try {
			return directory.getCanonicalFile().equals(projectsDirectory.getCanonicalFile());
		} catch (IOException e) {
			return directory.getAbsoluteFile().equals(projectsDirectory.getAbsoluteFile());
		}
	}

	public void save() throws IOException {
		if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
			throw new IOException("Could not create settings directory: " + SETTINGS_DIR.getPath());
		}
		JSONObject json = new JSONObject();
		json.put("projectsDirectory", projectsDirectory.getAbsolutePath());
		json.put("agentModel", agentModel);
		json.put("ollamaUrl", ollamaUrl);
		json.put("lmstudioUrl", lmstudioUrl);
		json.put("thinkingEnabled", thinkingEnabled);
		json.put("subtasksEnabled", subtasksEnabled);
		json.put("agentBrowserWebEnabled", agentBrowserWebEnabled);
		json.put("agentBrowserCommand", agentBrowserCommand);
		json.put("agentContextLength", agentContextLength);
		json.put("agentMaxOutputTokens", agentMaxOutputTokens);
		JSONArray skillIds = new JSONArray();
		for (String id : manualSkillIds) {
			skillIds.put(id);
		}
		json.put("manualSkillIds", skillIds);
		Files.writeString(SETTINGS_FILE.toPath(), json.toString(2), StandardCharsets.UTF_8);
	}

	public static File getSettingsFile() {
		return SETTINGS_FILE;
	}
}
