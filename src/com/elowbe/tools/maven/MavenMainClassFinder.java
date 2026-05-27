package com.elowbe.tools.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MavenMainClassFinder {
	private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
	private static final Pattern PUBLIC_CLASS = Pattern.compile("public\\s+class\\s+(\\w+)");

	private MavenMainClassFinder() {
	}

	static String findMainClass(File projectRoot) throws IOException {
		if (projectRoot == null) {
			return "";
		}
		Path srcMainJava = projectRoot.toPath().resolve("src/main/java");
		if (!Files.isDirectory(srcMainJava)) {
			return "";
		}

		List<String> candidates = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(srcMainJava)) {
			paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
				try {
					String candidate = mainClassFromSource(path, Files.readString(path));
					if (!candidate.isBlank()) {
						candidates.add(candidate);
					}
				} catch (IOException ignored) {
					// Skip unreadable sources.
				}
			});
		}
		if (candidates.isEmpty()) {
			return "";
		}
		if (candidates.size() == 1) {
			return candidates.get(0);
		}
		candidates.sort(Comparator.comparingInt(MavenMainClassFinder::mainClassPriority).reversed()
				.thenComparing(String::compareTo));
		return candidates.get(0);
	}

	private static String mainClassFromSource(Path path, String content) {
		if (!content.contains("public static void main")) {
			return "";
		}
		String className = path.getFileName().toString();
		if (!className.endsWith(".java")) {
			return "";
		}
		className = className.substring(0, className.length() - ".java".length());

		Matcher publicClass = PUBLIC_CLASS.matcher(content);
		if (publicClass.find() && !className.equals(publicClass.group(1))) {
			className = publicClass.group(1);
		}

		Matcher packageMatch = PACKAGE.matcher(content);
		if (packageMatch.find()) {
			return packageMatch.group(1) + "." + className;
		}
		return className;
	}

	private static int mainClassPriority(String className) {
		String simple = className.substring(className.lastIndexOf('.') + 1);
		if (simple.endsWith("App")) {
			return 3;
		}
		if (simple.endsWith("Main")) {
			return 2;
		}
		if (simple.contains("Application")) {
			return 1;
		}
		return 0;
	}
}
