package com.elowbe.main;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import lib.console.util.OllamaAPI;

/**
 * Uses an LLM call to select zero or more skills for a user task before the main agent run.
 */
public final class SkillSelector {
	private static final String SYSTEM_PROMPT = """
			You are a skill router for a coding agent.
			Given the user request and the skill catalog, choose which skill ids should guide the worker.
			Return JSON only: {"skills":["id1","id2"]} or {"skills":[]} when none apply.
			Use only id values from the catalog (not file paths, not SKILL.md).
			When a skill description clearly matches the task, include that skill's id.
			You may select multiple skills when the task spans them.
			""";

	private SkillSelector() {
	}

	public record SkillSelectionResult(List<Skill> skills, JSONObject usage, String note) {
	}

	@FunctionalInterface
	public interface UsageSink {
		void accept(JSONObject usage);
	}

	public static SkillSelectionResult select(String instruction, List<Skill> candidates, File runDirectory,
			BooleanSupplier cancelRequested, UsageSink usageSink) throws IOException {
		return select(instruction, candidates, runDirectory, cancelRequested, usageSink, true);
	}

	public static SkillSelectionResult select(String instruction, List<Skill> candidates, File runDirectory,
			BooleanSupplier cancelRequested, UsageSink usageSink, boolean thinkingEnabled) throws IOException {
		if (candidates == null || candidates.isEmpty()) {
			return new SkillSelectionResult(List.of(), null, "no skill candidates on disk");
		}
		if (cancelRequested != null && cancelRequested.getAsBoolean()) {
			throw new IOException("cancelled");
		}

		JSONArray messages = new JSONArray();
		messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
		messages.put(new JSONObject().put("role", "user").put("content", buildUserMessage(instruction, candidates, runDirectory)));

		throwIfCancelled(cancelRequested);
		JSONObject assistant = OllamaAPI.generateChatCompletion(messages, selectionSchema());
		throwIfCancelled(cancelRequested);
		if (usageSink != null && assistant.has("usage")) {
			usageSink.accept(assistant.getJSONObject("usage"));
		}

		String raw = extractModelText(assistant, thinkingEnabled);
		List<String> ids = parseSelectedIds(raw);
		List<Skill> selected = resolveSelected(ids, candidates, runDirectory);
		String note = buildNote(candidates, selected, ids, raw);
		return new SkillSelectionResult(selected, assistant.optJSONObject("usage"), note);
	}

	private static String buildNote(List<Skill> candidates, List<Skill> selected, List<String> ids, String raw) {
		if (!selected.isEmpty()) {
			return null;
		}
		if (ids.isEmpty()) {
			if (raw == null || raw.isBlank()) {
				return "LLM returned empty response";
			}
			return "LLM response could not be parsed (len=" + raw.length() + ")";
		}
		return "LLM ids did not match catalog: " + ids;
	}

	private static String buildUserMessage(String instruction, List<Skill> candidates, File runDirectory) {
		StringBuilder content = new StringBuilder();
		content.append("User request:\n").append(instruction == null ? "" : instruction.trim()).append("\n\n");
		content.append("Skill catalog (return id values in the skills array):\n");
		for (Skill skill : candidates) {
			content.append(Skill.formatSelectorCatalogEntry(skill, runDirectory)).append('\n');
		}
		return content.toString();
	}

	/** Qwen and other thinking models may put JSON in {@code thinking} instead of {@code content}. */
	private static String extractModelText(JSONObject assistant, boolean thinkingEnabled) {
		if (assistant == null) {
			return "";
		}
		String content = assistant.optString("content", "").trim();
		if (!thinkingEnabled) {
			return content;
		}
		String thinking = assistant.optString("thinking", "").trim();
		if (parseJson(content) != null) {
			return content;
		}
		if (parseJson(thinking) != null) {
			return thinking;
		}
		if (!content.isEmpty()) {
			return content;
		}
		return thinking;
	}

	private static List<String> parseSelectedIds(String content) {
		JSONObject parsed = parseJson(content);
		if (parsed == null) {
			return List.of();
		}
		if (!parsed.has("skills")) {
			return List.of();
		}
		Object skillsValue = parsed.get("skills");
		if (skillsValue == null || JSONObject.NULL.equals(skillsValue)) {
			return List.of();
		}
		if (skillsValue instanceof String text) {
			String id = text.trim();
			return id.isEmpty() ? List.of() : List.of(id);
		}
		if (!(skillsValue instanceof JSONArray array)) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			Object value = array.get(i);
			if (value == null || JSONObject.NULL.equals(value)) {
				continue;
			}
			if (value instanceof JSONObject object) {
				String id = firstNonBlank(
						object.optString("id", ""),
						object.optString("path", ""),
						object.optString("name", ""));
				if (!id.isBlank()) {
					ids.add(id.trim());
				}
				continue;
			}
			String id = String.valueOf(value).trim();
			if (!id.isEmpty()) {
				ids.add(id);
			}
		}
		return ids;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	public static List<Skill> resolveReferences(List<String> ids, List<Skill> candidates, File runDirectory) {
		return resolveSelected(ids, candidates, runDirectory);
	}

	private static List<Skill> resolveSelected(List<String> ids, List<Skill> candidates, File runDirectory) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<String, Skill> byKey = new LinkedHashMap<>();
		for (Skill skill : candidates) {
			byKey.put(Skill.normalizeName(skill.learnCatalogPath(runDirectory)), skill);
			byKey.put(Skill.normalizeName(skill.name), skill);
			if (skill.sourceFile != null && skill.sourceFile.getParentFile() != null) {
				byKey.put(Skill.normalizeName(skill.sourceFile.getParentFile().getName()), skill);
			}
		}

		List<Skill> selected = new ArrayList<>();
		for (String id : ids) {
			Skill skill = byKey.get(Skill.normalizeName(id));
			if (skill == null) {
				skill = Skill.findByNameInRunDirectory(runDirectory, id);
			}
			if (skill != null) {
				String dedupe = skill.displayPath();
				boolean duplicate = false;
				for (Skill existing : selected) {
					if (existing.displayPath().equals(dedupe)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate) {
					selected.add(skill);
				}
			}
		}
		return selected;
	}

	private static JSONObject parseJson(String content) {
		if (content == null) {
			return null;
		}
		String text = content.trim();
		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			int closing = text.lastIndexOf("```");
			if (firstNewline >= 0 && closing > firstNewline) {
				text = text.substring(firstNewline + 1, closing).trim();
			}
		}
		try {
			return new JSONObject(text);
		} catch (JSONException e) {
			int start = text.indexOf('{');
			int end = text.lastIndexOf('}');
			if (start >= 0 && end > start) {
				try {
					return new JSONObject(text.substring(start, end + 1));
				} catch (JSONException ignored) {
					return null;
				}
			}
			return null;
		}
	}

	private static void throwIfCancelled(BooleanSupplier cancelRequested) throws IOException {
		if (cancelRequested != null && cancelRequested.getAsBoolean()) {
			throw new IOException("cancelled");
		}
	}

	private static JSONObject selectionSchema() {
		return new JSONObject()
				.put("type", "object")
				.put("additionalProperties", false)
				.put("required", new JSONArray().put("skills"))
				.put("properties", new JSONObject().put("skills", new JSONObject()
						.put("type", "array")
						.put("items", new JSONObject().put("type", "string"))
						.put("description", "Skill ids from the catalog; empty array if none apply.")));
	}
}
