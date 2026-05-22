package com.elowbe.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import lib.console.util.OllamaAPI;

/**
 * Git helpers for the agent working directory: init, diff collection, staging,
 * restore, and AI-assisted commit messages.
 */
public final class GitService {
	public static final class FileChange {
		public final String path;
		public final String diff;
		public final boolean untracked;

		public FileChange(String path, String diff, boolean untracked) {
			this.path = path;
			this.diff = diff;
			this.untracked = untracked;
		}
	}

	private static final Pattern SUBJECT_BODY = Pattern.compile("(?s)^SUBJECT:\\s*(.+?)\\s*BODY:\\s*(.*)$");

	private GitService() {
	}

	public static boolean isRepository(File directory) {
		return new File(directory, ".git").exists();
	}

	public static void ensureRepository(File directory) throws IOException, InterruptedException {
		if (directory == null) {
			throw new IOException("No working directory");
		}
		if (!directory.exists() && !directory.mkdirs()) {
			throw new IOException("Could not create directory: " + directory.getPath());
		}
		if (isRepository(directory)) {
			return;
		}
		CommandResult init = runGit(directory, "init");
		if (init.exitCode != 0) {
			throw new IOException("git init failed: " + init.combinedOutput());
		}
	}

	public static List<FileChange> listChanges(File directory) throws IOException, InterruptedException {
		ensureRepository(directory);
		List<FileChange> changes = new ArrayList<>();
		Set<String> seenPaths = new LinkedHashSet<>();

		CommandResult untracked = runGit(directory, "ls-files", "--others", "--exclude-standard");
		if (untracked.exitCode != 0) {
			throw new IOException("git ls-files failed: " + untracked.combinedOutput());
		}
		for (String path : untracked.stdout.split("\n")) {
			path = path.trim();
			if (path.isEmpty()) {
				continue;
			}
			File file = new File(directory, path);
			if (!file.isFile()) {
				continue;
			}
			seenPaths.add(path);
			changes.add(new FileChange(path, diffUntrackedFile(directory, path), true));
		}

		CommandResult status = runGit(directory, "status", "--porcelain");
		if (status.exitCode != 0) {
			throw new IOException("git status failed: " + status.combinedOutput());
		}

		for (String line : status.stdout.split("\n")) {
			if (line.length() < 4) {
				continue;
			}
			String statusCode = line.substring(0, 2);
			if (statusCode.startsWith("?")) {
				continue;
			}
			String path = line.substring(3).trim();
			if (path.contains(" -> ")) {
				path = path.substring(path.indexOf(" -> ") + 4);
			}
			if (path.isEmpty() || seenPaths.contains(path)) {
				continue;
			}
			File file = new File(directory, path);
			if (file.isDirectory()) {
				collectUntrackedFilesInDirectory(directory, file, path, seenPaths, changes);
				continue;
			}
			seenPaths.add(path);
			changes.add(new FileChange(path, diffTrackedFile(directory, path), false));
		}
		return changes;
	}

	public static void stageFile(File directory, String path) throws IOException, InterruptedException {
		CommandResult result = runGit(directory, "add", "--", path);
		if (result.exitCode != 0) {
			throw new IOException("git add failed for " + path + ": " + result.combinedOutput());
		}
	}

	public static void rejectFile(File directory, FileChange change) throws IOException, InterruptedException {
		if (change.untracked) {
			File file = new File(directory, change.path);
			if (!file.exists()) {
				return;
			}
			if (file.isDirectory()) {
				deleteRecursively(file.toPath());
				return;
			}
			if (!file.delete()) {
				throw new IOException("Could not delete untracked file: " + change.path);
			}
			return;
		}
		CommandResult result = runGit(directory, "restore", "--", change.path);
		if (result.exitCode != 0) {
			result = runGit(directory, "checkout", "--", change.path);
		}
		if (result.exitCode != 0) {
			throw new IOException("git restore failed for " + change.path + ": " + result.combinedOutput());
		}
	}

	public static String stagedDiffSummary(File directory) throws IOException, InterruptedException {
		CommandResult stat = runGit(directory, "diff", "--cached", "--stat");
		if (stat.exitCode != 0) {
			throw new IOException("git diff --cached --stat failed: " + stat.combinedOutput());
		}
		CommandResult diff = runGit(directory, "diff", "--cached");
		if (diff.exitCode != 0) {
			throw new IOException("git diff --cached failed: " + diff.combinedOutput());
		}
		StringBuilder summary = new StringBuilder();
		if (!stat.stdout.isBlank()) {
			summary.append(stat.stdout.trim()).append("\n\n");
		}
		String patch = diff.stdout;
		if (patch.length() > 12000) {
			patch = patch.substring(0, 12000) + "\n... (truncated)";
		}
		summary.append(patch);
		return summary.toString().trim();
	}

	public static CommitMessage generateCommitMessage(String diffSummary) throws IOException {
		JSONArray messages = new JSONArray();
		messages.put(new JSONObject().put("role", "system").put("content",
				"You write concise git commit messages. Respond with exactly two lines using this format:\n"
						+ "SUBJECT: <imperative subject, max 72 chars, no quotes>\n"
						+ "BODY: <1-3 sentence description of why the change matters>"));
		messages.put(new JSONObject().put("role", "user").put("content",
				"Write a commit message for these staged changes:\n\n" + diffSummary));

		JSONObject response = OllamaAPI.generateChatCompletion(messages);
		String content = extractAssistantContent(response);
		return parseCommitMessage(content);
	}

	public static void commit(File directory, CommitMessage message) throws IOException, InterruptedException {
		if (message == null || message.subject.isBlank()) {
			throw new IOException("Commit message is empty");
		}
		CommandResult result = runGit(directory, "commit", "-m", message.subject, "-m", message.body);
		if (result.exitCode != 0) {
			throw new IOException("git commit failed: " + result.combinedOutput());
		}
	}

	public static final class CommitMessage {
		public final String subject;
		public final String body;

		public CommitMessage(String subject, String body) {
			this.subject = subject == null ? "" : subject.trim();
			this.body = body == null ? "" : body.trim();
		}
	}

	private static String diffTrackedFile(File directory, String path) throws IOException, InterruptedException {
		CommandResult result = runGit(directory, "diff", "--", path);
		if (result.exitCode != 0) {
			throw new IOException("git diff failed for " + path + ": " + result.combinedOutput());
		}
		return result.stdout.isBlank() ? "(no diff text)" : result.stdout;
	}

	private static void collectUntrackedFilesInDirectory(File root, File dir, String relativeDir, Set<String> seenPaths,
			List<FileChange> changes) throws IOException {
		File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			String relativePath = relativeDir.isEmpty() ? child.getName()
					: relativeDir.replace('\\', '/') + "/" + child.getName();
			if (child.isDirectory()) {
				collectUntrackedFilesInDirectory(root, child, relativePath, seenPaths, changes);
			} else if (child.isFile() && !seenPaths.contains(relativePath)) {
				seenPaths.add(relativePath);
				changes.add(new FileChange(relativePath, diffUntrackedFile(root, relativePath), true));
			}
		}
	}

	private static String diffUntrackedFile(File directory, String path) throws IOException {
		File file = new File(directory, path);
		if (file.isDirectory()) {
			return "(directory — no readable files found)";
		}
		if (!file.isFile()) {
			return "(missing file: " + path + ")";
		}
		String content;
		try {
			content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(binary or unreadable file: " + path + ")";
		}
		StringBuilder diff = new StringBuilder();
		diff.append("--- /dev/null\n");
		diff.append("+++ b/").append(path.replace('\\', '/')).append('\n');
		String[] lines = content.split("\n", -1);
		diff.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
		for (String line : lines) {
			diff.append('+').append(line).append('\n');
		}
		return diff.toString();
	}

	private static void deleteRecursively(Path path) throws IOException {
		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static CommitMessage parseCommitMessage(String content) {
		if (content == null || content.isBlank()) {
			return new CommitMessage("Update project files", "Changes reviewed and accepted in ElowbeAgent.");
		}
		Matcher matcher = SUBJECT_BODY.matcher(content.trim());
		if (matcher.matches()) {
			return new CommitMessage(matcher.group(1).trim(), matcher.group(2).trim());
		}
		String[] lines = content.trim().split("\n", 2);
		String subject = lines[0].trim();
		String body = lines.length > 1 ? lines[1].trim() : "";
		return new CommitMessage(stripPrefix(subject, "SUBJECT:"), stripPrefix(body, "BODY:"));
	}

	private static String stripPrefix(String value, String prefix) {
		if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return value.substring(prefix.length()).trim();
		}
		return value;
	}

	private static String extractAssistantContent(JSONObject response) {
		JSONObject message = response.optJSONObject("message");
		if (message != null) {
			return message.optString("content", "");
		}
		return response.optString("response", "");
	}

	private static CommandResult runGit(File directory, String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("git");
		for (String arg : args) {
			command.add(arg);
		}

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(directory);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		return new CommandResult(exitCode, output);
	}

	private static final class CommandResult {
		private final int exitCode;
		private final String stdout;

		private CommandResult(int exitCode, String stdout) {
			this.exitCode = exitCode;
			this.stdout = stdout == null ? "" : stdout;
		}

		private String combinedOutput() {
			return stdout.trim();
		}
	}
}
