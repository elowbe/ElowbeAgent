package com.elowbe.main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
		List<File> roots = new ArrayList<>();
		File start = workingDirectory == null ? new File(".") : workingDirectory;
		try {
			start = start.getCanonicalFile();
		} catch (IOException ignored) {
			start = start.getAbsoluteFile();
		}

		// Walk up from the working directory so project-level skills/ is found even when
		// the agent cwd is a subdirectory like agenttest/.
		File current = start;
		for (int depth = 0; depth < 6 && current != null; depth++) {
			addSkillDirectoryRoot(roots, new File(current, ".cursor/skills"));
			addSkillDirectoryRoot(roots, new File(current, "skills"));
			addSkillDirectoryRoot(roots, new File(current, "src/skills"));
			addSkillDirectoryRoot(roots, new File(current, "src/.cursor/skills"));
			current = current.getParentFile();
		}

		// Fallback install candidates relative to the JVM working directory.
		addSkillDirectoryRoot(roots, new File("skills"));
		addSkillDirectoryRoot(roots, new File("src/skills"));
		addSkillDirectoryRoot(roots, new File("src/.cursor/skills"));
		addSkillDirectoryRoot(roots, new File(".cursor/skills"));
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

	public static String formatCatalogEntry(Skill skill) {
		StringBuilder entry = new StringBuilder();
		entry.append("- ").append(skill.name);
		if (skill.hasDescription()) {
			entry.append(": ").append(skill.description);
		}
		entry.append(" [").append(skill.displayPath()).append(']');
		return entry.toString();
	}

	public static String normalizeName(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
