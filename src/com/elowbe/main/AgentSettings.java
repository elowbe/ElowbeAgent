package com.elowbe.main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.json.JSONObject;

/**
 * Persistent user settings for ElowbeAgent.
 */
public final class AgentSettings {
	private static final File SETTINGS_DIR = new File(System.getProperty("user.home"), ".elowbe-agent");
	private static final File SETTINGS_FILE = new File(SETTINGS_DIR, "settings.json");

	private File projectsDirectory;

	private AgentSettings(File projectsDirectory) {
		this.projectsDirectory = projectsDirectory;
	}

	public static File defaultProjectsDirectory() {
		return new File(System.getProperty("user.home"), "Documents/elowbe-projects");
	}

	public static AgentSettings load() {
		if (!SETTINGS_FILE.isFile()) {
			return new AgentSettings(defaultProjectsDirectory());
		}
		try {
			String raw = Files.readString(SETTINGS_FILE.toPath(), StandardCharsets.UTF_8);
			JSONObject json = new JSONObject(raw);
			String path = json.optString("projectsDirectory", "");
			File projectsDirectory = path.isBlank() ? defaultProjectsDirectory()
					: new File(path).getAbsoluteFile();
			return new AgentSettings(projectsDirectory);
		} catch (Exception e) {
			return new AgentSettings(defaultProjectsDirectory());
		}
	}

	public File getProjectsDirectory() {
		return projectsDirectory;
	}

	public void setProjectsDirectory(File projectsDirectory) throws IOException {
		if (projectsDirectory == null) {
			throw new IOException("Projects directory cannot be null");
		}
		this.projectsDirectory = projectsDirectory.getAbsoluteFile();
		save();
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

	private void save() throws IOException {
		if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
			throw new IOException("Could not create settings directory: " + SETTINGS_DIR.getPath());
		}
		JSONObject json = new JSONObject();
		json.put("projectsDirectory", projectsDirectory.getAbsolutePath());
		Files.writeString(SETTINGS_FILE.toPath(), json.toString(2), StandardCharsets.UTF_8);
	}
}
