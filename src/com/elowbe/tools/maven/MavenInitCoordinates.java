package com.elowbe.tools.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

final class MavenInitCoordinates {
	private static final String DEFAULT_GROUP_ID = "com.elowbe";

	private final String groupId;
	private final String artifactId;
	private final List<String> inferredFields;

	private MavenInitCoordinates(String groupId, String artifactId, List<String> inferredFields) {
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.inferredFields = inferredFields;
	}

	static MavenInitCoordinates resolve(JSONObject arguments, File workingDirectory) {
		List<String> inferred = new ArrayList<>();
		String groupId = first(arguments, "group_id", "groupId");
		String artifactId = first(arguments, "artifact_id", "artifactId");
		String name = first(arguments, "name");

		if (groupId.isBlank()) {
			groupId = DEFAULT_GROUP_ID;
			inferred.add("group_id=" + groupId);
		}

		if (artifactId.isBlank()) {
			String source = !name.isBlank() ? name : directoryName(workingDirectory);
			artifactId = slugifyArtifactId(source);
			if (!artifactId.isBlank()) {
				inferred.add("artifact_id=" + artifactId + " (from " + (!name.isBlank() ? "name" : "directory") + ")");
			}
		}

		return new MavenInitCoordinates(groupId, artifactId, inferred);
	}

	static String formatInferenceNote(MavenInitCoordinates coordinates) {
		if (coordinates.inferredFields.isEmpty()) {
			return "";
		}
		return "Inferred missing init fields: " + String.join(", ", coordinates.inferredFields) + ".\n";
	}

	String groupId() {
		return groupId;
	}

	String artifactId() {
		return artifactId;
	}

	boolean isValid() {
		return !groupId.isBlank() && !artifactId.isBlank();
	}

	String validationError() {
		if (groupId.isBlank() && artifactId.isBlank()) {
			return "maven init requires group_id and artifact_id. Example: "
					+ "{\"action\":\"init\",\"group_id\":\"com.elowbe\",\"artifact_id\":\"lwjgl-cube\",\"name\":\"LWJGL Cube\"}";
		}
		if (artifactId.isBlank()) {
			return "maven init requires artifact_id as its own JSON field (lowercase slug, e.g. lwjgl-cube). "
					+ "The name field is not a substitute. Example: "
					+ "{\"action\":\"init\",\"group_id\":\"" + groupId
					+ "\",\"artifact_id\":\"lwjgl-cube\",\"name\":\"LWJGL Cube\"}";
		}
		if (groupId.isBlank()) {
			return "maven init requires group_id. Example: "
					+ "{\"action\":\"init\",\"group_id\":\"com.elowbe\",\"artifact_id\":\"" + artifactId + "\"}";
		}
		return "";
	}

	static void apply(JSONObject arguments, MavenInitCoordinates coordinates) {
		arguments.put("group_id", coordinates.groupId());
		arguments.put("artifact_id", coordinates.artifactId());
	}

	private static String directoryName(File workingDirectory) {
		if (workingDirectory == null) {
			return "";
		}
		String name = workingDirectory.getName();
		return name == null ? "" : name.trim();
	}

	static String slugifyArtifactId(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String slug = value.trim().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		if (slug.isBlank()) {
			return "";
		}
		if (Character.isDigit(slug.charAt(0))) {
			slug = "app-" + slug;
		}
		return slug;
	}

	private static String first(JSONObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key)).trim();
			}
		}
		return "";
	}
}
