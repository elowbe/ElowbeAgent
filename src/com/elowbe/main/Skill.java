package com.elowbe.main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Cursor-style skill: markdown with optional YAML frontmatter ({@code name},
 * {@code description}) plus a body of agent instructions.
 */
public final class Skill {
	public final String name;
	public final String description;
	public final String body;
	public final File sourceFile;

	public Skill(String name, String description, String body, File sourceFile) {
		this.name = name == null || name.isBlank() ? defaultName(sourceFile) : name.trim();
		this.description = description == null ? "" : description.trim();
		this.body = body == null ? "" : body.trim();
		this.sourceFile = sourceFile;
	}

	public static Skill parse(File file) throws IOException {
		String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		return parse(content, file);
	}

	public static Skill parse(String content, File sourceFile) {
		String trimmed = content == null ? "" : content.trim();
		if (!trimmed.startsWith("---")) {
			return new Skill(defaultName(sourceFile), "", trimmed, sourceFile);
		}

		int end = trimmed.indexOf("\n---", 3);
		if (end < 0) {
			return new Skill(defaultName(sourceFile), "", trimmed, sourceFile);
		}

		String frontmatter = trimmed.substring(3, end).trim();
		String body = trimmed.substring(end + 4).trim();
		String name = defaultName(sourceFile);
		String description = "";
		String parsedName = parseFrontmatter(frontmatter, "name");
		if (parsedName != null && !parsedName.isBlank()) {
			name = parsedName.trim();
		}
		String parsedDescription = parseFrontmatter(frontmatter, "description");
		if (parsedDescription != null) {
			description = parsedDescription.trim();
		}
		return new Skill(name, description, body, sourceFile);
	}

	public static List<Skill> discoverInDirectory(File skillsDir) {
		List<Skill> skills = new ArrayList<>();
		if (skillsDir == null || !skillsDir.isDirectory()) {
			return skills;
		}
		File[] children = skillsDir.listFiles(File::isDirectory);
		if (children == null) {
			return skills;
		}
		for (File child : children) {
			File skillFile = new File(child, "SKILL.md");
			if (!skillFile.isFile()) {
				skillFile = new File(child, "skill.md");
			}
			if (!skillFile.isFile()) {
				continue;
			}
			try {
				skills.add(parse(skillFile));
			} catch (IOException ignored) {
				// Skip unreadable skill files.
			}
		}
		return skills;
	}

	private static String defaultName(File sourceFile) {
		if (sourceFile == null) {
			return "skill";
		}
		File parent = sourceFile.getParentFile();
		if (parent != null && !parent.getName().isBlank()) {
			return parent.getName();
		}
		String fileName = sourceFile.getName();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static String parseFrontmatter(String frontmatter, String... keys) {
		if (keys.length == 0) {
			return "";
		}
		String[] lines = frontmatter.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String key = line.substring(0, colon).trim();
			boolean matches = false;
			for (String wanted : keys) {
				if (wanted.equals(key)) {
					matches = true;
					break;
				}
			}
			if (!matches) {
				continue;
			}

			String value = line.substring(colon + 1).trim();
			if (value.equals(">-") || value.equals(">") || value.equals("|") || value.equals("|-")) {
				StringBuilder folded = new StringBuilder();
				for (int j = i + 1; j < lines.length; j++) {
					String next = lines[j];
					if (!next.isEmpty() && !Character.isWhitespace(next.charAt(0))) {
						break;
					}
					if (folded.length() > 0) {
						folded.append(' ');
					}
					folded.append(next.trim());
					i = j;
				}
				return folded.toString().trim();
			}
			return unquote(value);
		}
		return keys.length == 1 ? null : "";
	}

	private static String unquote(String value) {
		if (value == null) {
			return "";
		}
		value = value.trim();
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	public String displayPath() {
		return sourceFile == null ? name : sourceFile.getPath();
	}

	public boolean hasBody() {
		return body != null && !body.isBlank();
	}

	public boolean hasDescription() {
		return description != null && !description.isBlank();
	}

	@Override
	public String toString() {
		return name + " (" + displayPath() + ")";
	}

	public static File resolvePrimarySkillFile(File workingDirectory) {
		File[] workingCandidates = {
				new File(workingDirectory, "skill.md"),
				new File(workingDirectory, "SKILL.md"),
				new File(workingDirectory, ".cursor/skills/default/SKILL.md"),
		};
		for (File candidate : workingCandidates) {
			if (candidate.isFile()) {
				return candidate;
			}
		}

		File[] installCandidates = {
				new File("src/skill.md"),
				new File("src/SKILL.md"),
				new File("skill.md"),
				new File("SKILL.md"),
		};
		for (File candidate : installCandidates) {
			if (candidate.isFile()) {
				return candidate;
			}
		}
		return workingCandidates[0];
	}

	public static List<File> skillDirectoryRoots(File workingDirectory) {
		return skillDirectoryRoots(workingDirectory, true, true);
	}

	/**
	 * Skill roots visible to the agent: walk up from the run directory (e.g. find
	 * {@code skills/} on a parent), plus the ElowbeAgent install {@code skills/} folder.
	 */
	public static List<File> skillDirectoryRootsForAgent(File runDirectory) {
		List<File> roots = skillDirectoryRoots(runDirectory, true, false);
		File agentHome = resolveAgentHomeDirectory();
		if (agentHome != null) {
			addSkillDirectoryRoot(roots, new File(agentHome, "skills"));
			addSkillDirectoryRoot(roots, new File(agentHome, "src/skills"));
			addSkillDirectoryRoot(roots, new File(agentHome, ".cursor/skills"));
		}
		return roots;
	}

	/**
	 * ElowbeAgent install root: the directory that directly contains {@code skills/}.
	 * Walks up from the runtime classpath (e.g. {@code bin/} → project root), not the
	 * user's open project path. Does not treat copied {@code system.txt} in output
	 * folders as home—that would incorrectly stop at {@code bin/}.
	 */
	public static File resolveAgentHomeDirectory() {
		for (File seed : agentHomeSeeds()) {
			File current = canonical(seed);
			if (current == null) {
				continue;
			}
			if (current.isFile()) {
				current = current.getParentFile();
			}
			for (int depth = 0; depth < 10 && current != null; depth++) {
				if (new File(current, "skills").isDirectory()) {
					return current;
				}
				current = current.getParentFile();
			}
		}
		return null;
	}

	private static File[] agentHomeSeeds() {
		List<File> seeds = new ArrayList<>();
		try {
			var location = Skill.class.getProtectionDomain().getCodeSource().getLocation();
			if (location != null) {
				File code = new File(location.toURI());
				if (code.isDirectory()) {
					seeds.add(code);
				} else if (code.isFile()) {
					seeds.add(code.getParentFile());
				}
			}
		} catch (Exception ignored) {
			// Fall through to user.dir.
		}
		seeds.add(new File(System.getProperty("user.dir", ".")));
		return seeds.toArray(File[]::new);
	}

	/** Discover every skill under all agent skill roots for {@code runDirectory}. */
	public static List<Skill> discoverAll(File runDirectory) {
		Map<String, Skill> byPath = new LinkedHashMap<>();
		for (File skillsRoot : skillDirectoryRootsForAgent(runDirectory)) {
			for (Skill skill : discoverInDirectory(skillsRoot)) {
				byPath.put(skill.displayPath(), skill);
			}
		}
		return new ArrayList<>(byPath.values());
	}

	private static List<File> skillDirectoryRoots(File workingDirectory, boolean includeAncestors,
			boolean includeJvmFallbacks) {
		List<File> roots = new ArrayList<>();
		File start = workingDirectory == null ? new File(".") : workingDirectory;
		try {
			start = start.getCanonicalFile();
		} catch (IOException ignored) {
			start = start.getAbsoluteFile();
		}

		File current = start;
		int maxDepth = includeAncestors ? 6 : 1;
		for (int depth = 0; depth < maxDepth && current != null; depth++) {
			addSkillDirectoryRoot(roots, new File(current, ".cursor/skills"));
			addSkillDirectoryRoot(roots, new File(current, "skills"));
			addSkillDirectoryRoot(roots, new File(current, "src/skills"));
			addSkillDirectoryRoot(roots, new File(current, "src/.cursor/skills"));
			current = current.getParentFile();
		}

		if (includeJvmFallbacks) {
			addSkillDirectoryRoot(roots, new File("skills"));
			addSkillDirectoryRoot(roots, new File("src/skills"));
			addSkillDirectoryRoot(roots, new File("src/.cursor/skills"));
			addSkillDirectoryRoot(roots, new File(".cursor/skills"));
		}
		return roots;
	}

	private static void addSkillDirectoryRoot(List<File> roots, File dir) {
		if (dir.isDirectory()) {
			for (File existing : roots) {
				try {
					if (existing.getCanonicalFile().equals(dir.getCanonicalFile())) {
						return;
					}
				} catch (IOException ignored) {
					if (existing.getAbsolutePath().equals(dir.getAbsolutePath())) {
						return;
					}
				}
			}
			roots.add(dir);
		}
	}

	public static String formatCatalogEntry(Skill skill, File runDirectory) {
		StringBuilder entry = new StringBuilder();
		entry.append("- ").append(skill.name);
		if (skill.hasDescription()) {
			entry.append(": ").append(skill.description);
		}
		entry.append(" [").append(skill.learnCatalogPath(runDirectory)).append(']');
		return entry.toString();
	}

	public static String formatSelectorCatalogEntry(Skill skill, File runDirectory) {
		StringBuilder entry = new StringBuilder();
		entry.append("- id: ").append(skill.learnCatalogPath(runDirectory));
		entry.append(" | name: ").append(skill.name);
		if (skill.hasDescription()) {
			entry.append(" | ").append(skill.description);
		}
		return entry.toString();
	}

	/** Full skill document text for injection into the agent system prompt. */
	public static String formatSkillSupplement(Skill skill) throws IOException {
		if (skill == null) {
			return "";
		}
		StringBuilder block = new StringBuilder();
		block.append("### ").append(skill.name).append('\n');
		if (skill.hasDescription()) {
			block.append(skill.description).append('\n');
		}
		if (skill.hasBody()) {
			block.append(skill.body);
			if (!skill.body.endsWith("\n")) {
				block.append('\n');
			}
		} else if (skill.sourceFile != null && skill.sourceFile.isFile()) {
			String content = Files.readString(skill.sourceFile.toPath(), StandardCharsets.UTF_8);
			int end = content.indexOf("\n---", 3);
			if (content.trim().startsWith("---") && end >= 0) {
				content = content.substring(end + 4).trim();
			}
			block.append(content);
			if (!content.endsWith("\n")) {
				block.append('\n');
			}
		}
		return block.toString();
	}

	/**
	 * Relative skill folder for {@code learn} path= (e.g. {@code web} for
	 * {@code skills/web/SKILL.md}). Not a file path—SKILL.md is implied.
	 */
	public String learnCatalogPath(File runDirectory) {
		if (sourceFile == null) {
			return name;
		}
		File skillParent = canonical(sourceFile.getParentFile());
		if (skillParent == null) {
			return name;
		}
		for (File root : skillDirectoryRootsForAgent(runDirectory)) {
			File rootCanon = canonical(root);
			String rootPath = rootCanon.getPath();
			String parentPath = skillParent.getPath();
			if (parentPath.equals(rootPath)) {
				return name;
			}
			String prefix = rootPath + File.separator;
			if (parentPath.startsWith(prefix)) {
				String relative = parentPath.substring(prefix.length()).replace(File.separatorChar, '/');
				return relative.isBlank() ? name : relative;
			}
		}
		File runCanon = canonical(runDirectory);
		if (skillParent.equals(runCanon)) {
			return name;
		}
		return skillParent.getName();
	}

	/** Strip SKILL.md / skill.md suffixes; return a relative skill folder path. */
	public static String normalizeLearnPath(String path) {
		if (path == null) {
			return "";
		}
		String normalized = path.trim().replace('\\', '/');
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		if (lower.endsWith("/skill.md")) {
			normalized = normalized.substring(0, normalized.length() - "/skill.md".length());
		} else if ("skill.md".equals(lower)) {
			normalized = "";
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	/**
	 * Resolve a learn {@code path} to the skill file. Accepts a catalog folder name
	 * (e.g. {@code web}), a path ending in SKILL.md, or a directory containing the skill file.
	 */
	public static File resolveLearnTarget(String path, File runDirectory) throws IOException {
		String folderPath = normalizeLearnPath(path);
		if (folderPath.isBlank()) {
			throw new IOException("missing skill folder path");
		}

		File runCanon = canonical(runDirectory == null ? new File(".") : runDirectory);
		File direct = canonical(new File(runCanon, folderPath));
		File skillFile = skillFileInDirectory(direct);
		if (skillFile != null) {
			return skillFile;
		}

		for (File root : skillDirectoryRootsForAgent(runCanon)) {
			skillFile = skillFileInDirectory(canonical(new File(root, folderPath)));
			if (skillFile != null) {
				return skillFile;
			}
		}

		Skill byLookup = findByNameInRunDirectory(runCanon, folderPath);
		if (byLookup != null && byLookup.sourceFile != null && byLookup.sourceFile.isFile()) {
			return byLookup.sourceFile;
		}
		throw new IOException("no skill at folder path \"" + folderPath + "\" (see catalog brackets for valid paths)");
	}

	private static File skillFileInDirectory(File dir) {
		if (dir == null) {
			return null;
		}
		if (dir.isFile() && isSkillFile(dir)) {
			return dir;
		}
		if (!dir.isDirectory()) {
			return null;
		}
		File skillMd = new File(dir, "SKILL.md");
		if (skillMd.isFile()) {
			return skillMd;
		}
		skillMd = new File(dir, "skill.md");
		if (skillMd.isFile()) {
			return skillMd;
		}
		return null;
	}

	private static File canonical(File file) {
		if (file == null) {
			return null;
		}
		try {
			return file.getCanonicalFile();
		} catch (IOException e) {
			return file.getAbsoluteFile();
		}
	}

	public static String normalizeName(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	public static boolean isSkillFile(File file) {
		if (file == null || !file.isFile()) {
			return false;
		}
		String fileName = file.getName().toLowerCase(Locale.ROOT);
		return "skill.md".equals(fileName);
	}

	/**
	 * Find a discovered or primary skill by catalog name (case-insensitive).
	 * Includes parent directories and JVM install fallbacks for UI catalog discovery.
	 */
	public static Skill findByName(File workingDirectory, String name) {
		return findByName(workingDirectory, name, true);
	}

	/**
	 * Find a skill for the learn tool: same discovery scope as the catalog (walk up from
	 * run directory). Matches frontmatter name, catalog folder in brackets, or folder name.
	 */
	public static Skill findByNameInRunDirectory(File runDirectory, String name) {
		String normalized = normalizeName(name);
		if (normalized.isEmpty()) {
			return null;
		}

		File current = canonical(runDirectory == null ? new File(".") : runDirectory);
		for (int depth = 0; depth < 6 && current != null; depth++) {
			File primaryFile = resolvePrimarySkillFileInRunDirectory(current);
			if (primaryFile.isFile()) {
				try {
					Skill skill = parse(primaryFile);
					if (matchesLearnLookup(skill, runDirectory, normalized)) {
						return skill;
					}
				} catch (IOException ignored) {
					// Try next location.
				}
			}
			current = current.getParentFile();
		}

		for (File root : skillDirectoryRootsForAgent(runDirectory)) {
			for (Skill skill : discoverInDirectory(root)) {
				if (matchesLearnLookup(skill, runDirectory, normalized)) {
					return skill;
				}
			}
		}
		return null;
	}

	private static boolean matchesLearnLookup(Skill skill, File runDirectory, String normalized) {
		if (normalizeName(skill.name).equals(normalized)) {
			return true;
		}
		String catalogPath = normalizeName(skill.learnCatalogPath(runDirectory));
		if (!catalogPath.isEmpty() && catalogPath.equals(normalized)) {
			return true;
		}
		if (skill.sourceFile != null) {
			File parent = skill.sourceFile.getParentFile();
			if (parent != null && normalizeName(parent.getName()).equals(normalized)) {
				return true;
			}
		}
		return false;
	}

	private static Skill findByName(File directory, String name, boolean includeJvmFallbacks) {
		String normalized = normalizeName(name);
		if (normalized.isEmpty()) {
			return null;
		}

		File primaryFile = includeJvmFallbacks
				? resolvePrimarySkillFile(directory)
				: resolvePrimarySkillFileInRunDirectory(directory);
		if (primaryFile.isFile()) {
			try {
				Skill skill = parse(primaryFile);
				if (matchesLearnLookup(skill, directory, normalized)) {
					return skill;
				}
			} catch (IOException ignored) {
				// Fall through to discovered skills.
			}
		}

		List<File> roots = includeJvmFallbacks
				? skillDirectoryRoots(directory)
				: skillDirectoryRootsForAgent(directory);
		for (File root : roots) {
			for (Skill skill : discoverInDirectory(root)) {
				if (matchesLearnLookup(skill, directory, normalized)) {
					return skill;
				}
			}
		}
		return null;
	}

	private static File resolvePrimarySkillFileInRunDirectory(File runDirectory) {
		if (runDirectory == null) {
			runDirectory = new File(".");
		}
		File[] candidates = {
				new File(runDirectory, "skill.md"),
				new File(runDirectory, "SKILL.md"),
				new File(runDirectory, ".cursor/skills/default/SKILL.md"),
		};
		for (File candidate : candidates) {
			if (candidate.isFile()) {
				return candidate;
			}
		}
		return candidates[0];
	}

	/** Full raw file contents for a skill (never truncated). */
	public static String formatLearnOutput(Skill skill) throws IOException {
		if (skill == null || skill.sourceFile == null || !skill.sourceFile.isFile()) {
			throw new IOException("skill file not found");
		}
		String content = Files.readString(skill.sourceFile.toPath(), StandardCharsets.UTF_8);
		StringBuilder output = new StringBuilder();
		output.append("Skill: ").append(skill.name);
		if (skill.hasDescription()) {
			output.append(" — ").append(skill.description);
		}
		output.append('\n').append("File: ").append(skill.sourceFile.getPath()).append('\n');
		output.append("---\n");
		output.append(content);
		if (!content.endsWith("\n")) {
			output.append('\n');
		}
		return output.toString();
	}
}
