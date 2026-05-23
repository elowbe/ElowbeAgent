package com.elowbe.tools.maven;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public final class PomModel {
	static final String POM_NS = "http://maven.apache.org/POM/4.0.0";
	private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

	private final File pomFile;
	private final Document document;
	private final Element project;

	private PomModel(File pomFile, Document document) {
		this.pomFile = pomFile;
		this.document = document;
		this.project = document.getDocumentElement();
	}

	public static PomModel load(File pomFile) throws IOException {
		if (pomFile == null || !pomFile.isFile()) {
			throw new IOException("pom.xml not found: " + (pomFile == null ? "(null)" : pomFile.getPath()));
		}
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(pomFile);
			return new PomModel(pomFile, document);
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("Failed to parse pom.xml: " + e.getMessage(), e);
		}
	}

	public static PomModel createNew(File pomFile, String groupId, String artifactId, String version,
			String javaVersion, String name, String description, String packaging) throws IOException {
		if (groupId == null || groupId.isBlank()) {
			throw new IOException("group_id is required");
		}
		if (artifactId == null || artifactId.isBlank()) {
			throw new IOException("artifact_id is required");
		}
		if (pomFile.exists()) {
			throw new IOException("pom.xml already exists: " + pomFile.getPath());
		}

		String resolvedVersion = blankToDefault(version, "1.0.0-SNAPSHOT");
		String resolvedJava = blankToDefault(javaVersion, "17");
		String resolvedPackaging = blankToDefault(packaging, "jar");

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.newDocument();

			Element root = document.createElementNS(POM_NS, "project");
			root.setAttribute("xmlns", POM_NS);
			root.setAttribute("xmlns:xsi", XSI_NS);
			root.setAttributeNS(XSI_NS, "xsi:schemaLocation",
					POM_NS + " http://maven.apache.org/xsd/maven-4.0.0.xsd");
			document.appendChild(root);

			appendChildText(document, root, "modelVersion", "4.0.0");
			appendChildText(document, root, "groupId", groupId.trim());
			appendChildText(document, root, "artifactId", artifactId.trim());
			appendChildText(document, root, "version", resolvedVersion);
			appendChildText(document, root, "packaging", resolvedPackaging);
			if (name != null && !name.isBlank()) {
				appendChildText(document, root, "name", name.trim());
			}
			if (description != null && !description.isBlank()) {
				appendChildText(document, root, "description", description.trim());
			}

			Element properties = ensureChild(document, root, "properties");
			appendChildText(document, properties, "maven.compiler.source", resolvedJava);
			appendChildText(document, properties, "maven.compiler.target", resolvedJava);
			appendChildText(document, properties, "project.build.sourceEncoding", "UTF-8");

			Element build = ensureChild(document, root, "build");
			Element plugins = ensureChild(document, build, "plugins");
			Element compilerPlugin = createPluginElement(document, "org.apache.maven.plugins",
					"maven-compiler-plugin", "3.13.0");
			plugins.appendChild(compilerPlugin);

			PomModel model = new PomModel(pomFile, document);
			model.save();
			return model;
		} catch (ParserConfigurationException e) {
			throw new IOException("Failed to create pom.xml: " + e.getMessage(), e);
		}
	}

	public File pomFile() {
		return pomFile;
	}

	public void save() throws IOException {
		File parent = pomFile.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		try {
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
			transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
			transformer.transform(new DOMSource(document), new StreamResult(pomFile));
		} catch (TransformerException e) {
			throw new IOException("Failed to write pom.xml: " + e.getMessage(), e);
		}
	}

	public String summarize() {
		StringBuilder summary = new StringBuilder();
		summary.append("pom: ").append(pomFile.getPath()).append('\n');
		summary.append("coordinates: ")
				.append(text("groupId")).append(':')
				.append(text("artifactId")).append(':')
				.append(text("version"));
		String packaging = text("packaging");
		if (!packaging.isBlank()) {
			summary.append(" (").append(packaging).append(')');
		}
		summary.append('\n');

		String name = text("name");
		if (!name.isBlank()) {
			summary.append("name: ").append(name).append('\n');
		}
		String description = text("description");
		if (!description.isBlank()) {
			summary.append("description: ").append(description).append('\n');
		}

		Element properties = child(project, "properties");
		if (properties != null) {
			summary.append("properties:\n");
			for (Element property : childElements(properties)) {
				summary.append("  ").append(localName(property)).append('=')
						.append(property.getTextContent().trim()).append('\n');
			}
		}

		Element dependencies = child(project, "dependencies");
		if (dependencies != null) {
			summary.append("dependencies:\n");
			for (Element dependency : childElements(dependencies, "dependency")) {
				summary.append("  - ").append(childText(dependency, "groupId")).append(':')
						.append(childText(dependency, "artifactId")).append(':')
						.append(childText(dependency, "version"));
				String scope = childText(dependency, "scope");
				if (!scope.isBlank()) {
					summary.append(" (scope=").append(scope).append(')');
				}
				summary.append('\n');
			}
		} else {
			summary.append("dependencies: (none)\n");
		}

		Element build = child(project, "build");
		Element plugins = build == null ? null : child(build, "plugins");
		if (plugins != null) {
			summary.append("plugins:\n");
			for (Element plugin : childElements(plugins, "plugin")) {
				summary.append("  - ").append(childText(plugin, "groupId")).append(':')
						.append(childText(plugin, "artifactId"));
				String version = childText(plugin, "version");
				if (!version.isBlank()) {
					summary.append(':').append(version);
				}
				summary.append('\n');
			}
		} else {
			summary.append("plugins: (none)\n");
		}
		return summary.toString().trim();
	}

	public void setCoordinate(String tag, String value) throws IOException {
		if (value == null || value.isBlank()) {
			return;
		}
		setChildText(project, tag, value.trim());
	}

	public void setProperty(String name, String value) throws IOException {
		if (name == null || name.isBlank()) {
			throw new IOException("property name is required");
		}
		if (value == null) {
			throw new IOException("property value is required");
		}
		Element properties = ensureChild(document, project, "properties");
		setChildText(properties, name.trim(), value);
	}

	public void addDependency(DependencySpec spec) throws IOException {
		Objects.requireNonNull(spec, "dependency");
		if (spec.groupId().isBlank() || spec.artifactId().isBlank() || spec.version().isBlank()) {
			throw new IOException("dependency group_id, artifact_id, and version are required");
		}

		Element dependencies = ensureChild(document, project, "dependencies");
		Element existing = findDependency(dependencies, spec.groupId(), spec.artifactId());
		Element dependency = existing == null ? document.createElementNS(POM_NS, "dependency") : existing;
		if (existing == null) {
			dependencies.appendChild(dependency);
		}
		setChildText(dependency, "groupId", spec.groupId());
		setChildText(dependency, "artifactId", spec.artifactId());
		setChildText(dependency, "version", spec.version());
		setOptionalChildText(dependency, "scope", spec.scope());
		setOptionalChildText(dependency, "type", spec.type());
		setOptionalChildText(dependency, "classifier", spec.classifier());
		if (spec.optional()) {
			setChildText(dependency, "optional", "true");
		} else {
			removeChild(dependency, "optional");
		}
	}

	public boolean removeDependency(String groupId, String artifactId) {
		Element dependencies = child(project, "dependencies");
		if (dependencies == null) {
			return false;
		}
		Element existing = findDependency(dependencies, groupId, artifactId);
		if (existing == null) {
			return false;
		}
		dependencies.removeChild(existing);
		return true;
	}

	public void addPlugin(PluginSpec spec) throws IOException {
		Objects.requireNonNull(spec, "plugin");
		if (spec.artifactId().isBlank()) {
			throw new IOException("plugin artifact_id is required");
		}
		String groupId = spec.groupId().isBlank() ? "org.apache.maven.plugins" : spec.groupId();

		Element build = ensureChild(document, project, "build");
		Element plugins = ensureChild(document, build, "plugins");
		Element existing = findPlugin(plugins, groupId, spec.artifactId());
		Element plugin = existing == null ? document.createElementNS(POM_NS, "plugin") : existing;
		if (existing == null) {
			plugins.appendChild(plugin);
		}
		setChildText(plugin, "groupId", groupId);
		setChildText(plugin, "artifactId", spec.artifactId());
		setOptionalChildText(plugin, "version", spec.version());
		if (spec.configurationXml() != null && !spec.configurationXml().isBlank()) {
			replaceConfiguration(plugin, spec.configurationXml());
		}
		if (spec.executions() != null && !spec.executions().isEmpty()) {
			replaceExecutions(plugin, spec.executions());
		}
	}

	public boolean removePlugin(String groupId, String artifactId) {
		Element build = child(project, "build");
		Element plugins = build == null ? null : child(build, "plugins");
		if (plugins == null) {
			return false;
		}
		String resolvedGroup = groupId == null || groupId.isBlank() ? "org.apache.maven.plugins" : groupId;
		Element existing = findPlugin(plugins, resolvedGroup, artifactId);
		if (existing == null) {
			return false;
		}
		plugins.removeChild(existing);
		return true;
	}

	private void replaceConfiguration(Element plugin, String configurationXml) throws IOException {
		removeChild(plugin, "configuration");
		Element configuration = document.createElementNS(POM_NS, "configuration");
		importFragmentChildren(configuration, configurationXml);
		plugin.appendChild(configuration);
	}

	private void replaceExecutions(Element plugin, List<PluginExecutionSpec> executions) throws IOException {
		removeChild(plugin, "executions");
		Element executionsElement = document.createElementNS(POM_NS, "executions");
		for (PluginExecutionSpec execution : executions) {
			Element executionElement = document.createElementNS(POM_NS, "execution");
			setOptionalChildText(executionElement, "id", execution.id());
			setOptionalChildText(executionElement, "phase", execution.phase());
			if (execution.goals() != null && !execution.goals().isEmpty()) {
				Element goals = document.createElementNS(POM_NS, "goals");
				for (String goal : execution.goals()) {
					if (goal != null && !goal.isBlank()) {
						appendChildText(document, goals, "goal", goal.trim());
					}
				}
				executionElement.appendChild(goals);
			}
			if (execution.configurationXml() != null && !execution.configurationXml().isBlank()) {
				Element configuration = document.createElementNS(POM_NS, "configuration");
				importFragmentChildren(configuration, execution.configurationXml());
				executionElement.appendChild(configuration);
			}
			executionsElement.appendChild(executionElement);
		}
		plugin.appendChild(executionsElement);
	}

	private void importFragmentChildren(Element target, String xmlFragment) throws IOException {
		String wrapped = "<wrapper xmlns=\"" + POM_NS + "\">" + xmlFragment.trim() + "</wrapper>";
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document fragment = builder.parse(new InputSource(new StringReader(wrapped)));
			Element wrapper = fragment.getDocumentElement();
			NodeList children = wrapper.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node imported = document.importNode(children.item(i), true);
				target.appendChild(imported);
			}
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("Invalid plugin configuration XML: " + e.getMessage(), e);
		}
	}

	private Element findDependency(Element dependencies, String groupId, String artifactId) {
		for (Element dependency : childElements(dependencies, "dependency")) {
			if (groupId.equals(childText(dependency, "groupId"))
					&& artifactId.equals(childText(dependency, "artifactId"))) {
				return dependency;
			}
		}
		return null;
	}

	private Element findPlugin(Element plugins, String groupId, String artifactId) {
		for (Element plugin : childElements(plugins, "plugin")) {
			if (groupId.equals(childText(plugin, "groupId"))
					&& artifactId.equals(childText(plugin, "artifactId"))) {
				return plugin;
			}
		}
		return null;
	}

	private String text(String tag) {
		return childText(project, tag);
	}

	private Element child(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagNameNS(POM_NS, tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getParentNode() == parent) {
				return (Element) node;
			}
		}
		return null;
	}

	private static String childText(Element parent, String tag) {
		Element child = childDirect(parent, tag);
		return child == null ? "" : child.getTextContent().trim();
	}

	private static Element childDirect(Element parent, String tag) {
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(localName((Element) node))) {
				return (Element) node;
			}
		}
		return null;
	}

	private static List<Element> childElements(Element parent) {
		List<Element> elements = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				elements.add((Element) node);
			}
		}
		return elements;
	}

	private static List<Element> childElements(Element parent, String tag) {
		List<Element> elements = new ArrayList<>();
		for (Element child : childElements(parent)) {
			if (tag.equals(localName(child))) {
				elements.add(child);
			}
		}
		return elements;
	}

	private static String localName(Element element) {
		String local = element.getLocalName();
		return local == null ? element.getTagName() : local;
	}

	private void setChildText(Element parent, String tag, String value) {
		Element child = childDirect(parent, tag);
		if (child == null) {
			appendChildText(document, parent, tag, value);
		} else {
			child.setTextContent(value);
		}
	}

	private static void setOptionalChildText(Element parent, String tag, String value) {
		if (value == null || value.isBlank()) {
			removeChild(parent, tag);
			return;
		}
		Element child = childDirect(parent, tag);
		if (child == null) {
			appendChildText(parent.getOwnerDocument(), parent, tag, value.trim());
		} else {
			child.setTextContent(value.trim());
		}
	}

	private static void removeChild(Element parent, String tag) {
		Element child = childDirect(parent, tag);
		if (child != null) {
			parent.removeChild(child);
		}
	}

	private static Element ensureChild(Document document, Element parent, String tag) {
		Element child = childDirect(parent, tag);
		if (child != null) {
			return child;
		}
		child = document.createElementNS(POM_NS, tag);
		parent.appendChild(child);
		return child;
	}

	private static void appendChildText(Document document, Element parent, String tag, String value) {
		Element child = document.createElementNS(POM_NS, tag);
		child.setTextContent(value);
		parent.appendChild(child);
	}

	private static Element createPluginElement(Document document, String groupId, String artifactId, String version) {
		Element plugin = document.createElementNS(POM_NS, "plugin");
		appendChildText(document, plugin, "groupId", groupId);
		appendChildText(document, plugin, "artifactId", artifactId);
		if (version != null && !version.isBlank()) {
			appendChildText(document, plugin, "version", version);
		}
		return plugin;
	}

	private static String blankToDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	public record DependencySpec(String groupId, String artifactId, String version, String scope, String type,
			String classifier, boolean optional) {
	}

	public record PluginExecutionSpec(String id, String phase, List<String> goals, String configurationXml) {
	}

	public record PluginSpec(String groupId, String artifactId, String version, String configurationXml,
			List<PluginExecutionSpec> executions) {
	}

	public static Map<String, String> parseCoordinates(org.json.JSONObject object) {
		Map<String, String> coordinates = new LinkedHashMap<>();
		if (object == null) {
			return coordinates;
		}
		putIfPresent(coordinates, "groupId", first(object, "group_id", "groupId"));
		putIfPresent(coordinates, "artifactId", first(object, "artifact_id", "artifactId"));
		putIfPresent(coordinates, "version", first(object, "version"));
		putIfPresent(coordinates, "name", first(object, "name"));
		putIfPresent(coordinates, "description", first(object, "description"));
		putIfPresent(coordinates, "packaging", first(object, "packaging"));
		return coordinates;
	}

	private static void putIfPresent(Map<String, String> target, String key, String value) {
		if (value != null && !value.isBlank()) {
			target.put(key, value.trim());
		}
	}

	private static String first(org.json.JSONObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key));
			}
		}
		return "";
	}
}
