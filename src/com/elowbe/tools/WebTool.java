package com.elowbe.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public final class WebTool {
	private static final int DEFAULT_TIMEOUT_SECONDS = 30;
	private static final int MAX_TEXT_CHARS = 8_000;
	private static final int MAX_BODY_CHARS = 16_000;
	private static final int MAX_LINKS = 30;
	private static final List<SearchProvider> SEARCH_PROVIDERS = List.of(
			new SearchProvider("bing", "https://www.bing.com/search?q="),
			new SearchProvider("mojeek", "https://www.mojeek.com/search?q="),
			new SearchProvider("brave", "https://search.brave.com/search?q="));
	private static volatile boolean agentBrowserCliEnabled;
	private static volatile String agentBrowserCommand = "agent-browser --json";

	private WebTool() {
	}

	public static void configureAgentBrowserCli(boolean enabled, String command) {
		agentBrowserCliEnabled = enabled;
		if (command != null && !command.isBlank()) {
			agentBrowserCommand = command.trim();
		}
	}

	public static boolean isAgentBrowserCliEnabled() {
		return agentBrowserCliEnabled;
	}

	public static String getAgentBrowserCommand() {
		return agentBrowserCommand;
	}

	public static ToolResult execute(JSONObject arguments, File workingDirectory, BooleanSupplier cancelRequested)
			throws IOException {
		if (arguments == null) {
			arguments = new JSONObject();
		}
		if (cancelRequested == null) {
			cancelRequested = () -> false;
		}
		if (agentBrowserCliEnabled) {
			return executeAgentBrowserCli(arguments, workingDirectory, cancelRequested);
		}

		String action = first(arguments, "action").toLowerCase(Locale.ROOT);
		if (action.isBlank()) {
			action = first(arguments, "query").isBlank() ? "open" : "search";
		}
		return switch (action) {
		case "open", "load", "follow" -> open(arguments, cancelRequested);
		case "search" -> search(arguments, cancelRequested);
		case "api", "request", "fetch" -> api(arguments, cancelRequested);
		default -> ToolResult.output("web: unknown action: " + action);
		};
	}

	public static String describeAction(JSONObject arguments) {
		if (arguments == null) {
			arguments = new JSONObject();
		}
		String action = first(arguments, "action");
		if (action.isBlank()) {
			action = first(arguments, "query").isBlank() ? "open" : "search";
		}
		return switch (action.toLowerCase(Locale.ROOT)) {
		case "search" -> "→ web search" + backendLabel() + ": " + truncateInline(first(arguments, "query"), 100);
		case "api", "request", "fetch" -> "→ web api" + backendLabel() + ": "
				+ first(arguments, "method", "http_method", "verb").toUpperCase(Locale.ROOT)
				+ " " + truncateInline(first(arguments, "url"), 100);
		default -> "→ web open" + backendLabel() + ": " + truncateInline(first(arguments, "url", "href"), 100);
		};
	}

	public static String describeResultSummary(String result) {
		if (result == null || result.isBlank()) {
			return "  (no web output)";
		}
		String trimmed = result.trim();
		if (trimmed.startsWith("{")) {
			try {
				String summary = describeJsonResult(new JSONObject(trimmed));
				if (!summary.isBlank()) {
					return "  " + truncateInline(summary, 160);
				}
			} catch (Exception ignored) {
			}
		}
		String firstLine = firstNonBlankLine(result);
		return "  " + truncateInline(firstLine, 160);
	}

	private static String describeJsonResult(JSONObject result) {
		String action = first(result, "action");
		String status = first(result, "status");
		StringBuilder summary = new StringBuilder("web");
		if (!action.isBlank()) {
			summary.append(' ').append(action);
		}
		if (!status.isBlank()) {
			summary.append(": ").append(status);
		}
		if (result.has("http_status")) {
			summary.append(" HTTP ").append(result.optInt("http_status"));
		}
		String title = first(result, "title");
		if (!title.isBlank()) {
			summary.append(" - ").append(title);
		}
		String url = first(result, "url");
		if (!url.isBlank()) {
			summary.append(" (").append(url).append(')');
		}
		String warning = first(result, "warning");
		if (!warning.isBlank()) {
			summary.append(" - ").append(warning);
		}
		String error = first(result, "error");
		if (!error.isBlank()) {
			summary.append(" - ").append(error);
		}
		return summary.toString();
	}

	private static String backendLabel() {
		return agentBrowserCliEnabled ? " (agent-browser)" : "";
	}

	private static ToolResult executeAgentBrowserCli(JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) throws IOException {
		String action = first(arguments, "action").toLowerCase(Locale.ROOT);
		if (action.isBlank()) {
			action = first(arguments, "query").isBlank() ? "open" : "search";
		}
		return switch (action) {
		case "open", "load", "follow" -> openAgentBrowser(arguments, workingDirectory, cancelRequested);
		case "search" -> searchAgentBrowser(arguments, workingDirectory, cancelRequested);
		case "api", "request", "fetch" -> apiAgentBrowser(arguments, workingDirectory, cancelRequested);
		default -> ToolResult.output("web: unknown action: " + action);
		};
	}

	private static ToolResult searchAgentBrowser(JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) throws IOException {
		String query = first(arguments, "query", "q", "search");
		if (query.isBlank()) {
			return ToolResult.output("web: missing query");
		}
		JSONObject lastResult = null;
		for (SearchProvider provider : SEARCH_PROVIDERS) {
			JSONObject openArgs = new JSONObject(arguments.toString())
					.put("url", provider.searchUrl(query))
					.put("action", "open");
			JSONObject result = loadPageAgentBrowser(openArgs, workingDirectory, cancelRequested);
			result.put("action", "search");
			result.put("query", query);
			result.put("search_provider", provider.name);
			if (cancelRequested.getAsBoolean()) {
				return ToolResult.output(result.toString(2));
			}
			if (!looksLikeCaptchaBlock(result)) {
				return ToolResult.output(result.toString(2));
			}
			result.put("status", "blocked");
			result.put("warning", "Search provider returned a captcha or bot-check page; trying fallback provider.");
			lastResult = result;
		}
		if (lastResult == null) {
			lastResult = new JSONObject()
					.put("status", "error")
					.put("action", "search")
					.put("query", query)
					.put("error", "No search providers are configured.");
		} else {
			lastResult.put("warning", "All configured search providers returned captcha or bot-check pages.");
		}
		return ToolResult.output(lastResult.toString(2));
	}

	private static ToolResult openAgentBrowser(JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) throws IOException {
		String url = normalizeUrl(first(arguments, "url", "href"));
		if (url.isBlank()) {
			return ToolResult.output("web: missing url");
		}
		String action = first(arguments, "action");
		JSONObject result = loadPageAgentBrowser(arguments.put("url", url), workingDirectory, cancelRequested);
		result.put("action", action.isBlank() ? "open" : action);
		return ToolResult.output(result.toString(2));
	}

	private static JSONObject loadPageAgentBrowser(JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) throws IOException {
		int timeoutSeconds = firstInt(arguments, DEFAULT_TIMEOUT_SECONDS, "timeout_seconds", "timeout");
		try {
			runAgentBrowserCommand(List.of("open", first(arguments, "url")), workingDirectory, timeoutSeconds,
					cancelRequested);
			if (cancelRequested.getAsBoolean()) {
				return new JSONObject().put("status", "cancelled");
			}
			waitForAgentBrowserLoad(workingDirectory, timeoutSeconds, cancelRequested);
			followRequestedLinkAgentBrowser(arguments, workingDirectory, timeoutSeconds, cancelRequested);
			if (cancelRequested.getAsBoolean()) {
				return new JSONObject().put("status", "cancelled");
			}
			return collectAgentBrowserPage(workingDirectory, arguments, timeoutSeconds, cancelRequested);
		} finally {
			try {
				runAgentBrowserCommand(List.of("close"), workingDirectory, Math.min(5, Math.max(1, timeoutSeconds)),
						() -> false);
			} catch (IOException ignored) {
			}
		}
	}

	private static void waitForAgentBrowserLoad(File workingDirectory, int timeoutSeconds,
			BooleanSupplier cancelRequested) {
		try {
			runAgentBrowserCommand(List.of("wait", "--load", "networkidle"), workingDirectory, timeoutSeconds,
					cancelRequested);
		} catch (IOException ignored) {
			// Some pages keep the network busy; page collection below still returns observable state.
		}
	}

	private static void followRequestedLinkAgentBrowser(JSONObject arguments, File workingDirectory,
			int timeoutSeconds, BooleanSupplier cancelRequested) throws IOException {
		String linkUrl = first(arguments, "link_url", "follow_url");
		if (!linkUrl.isBlank()) {
			String currentUrl = extractAgentBrowserText(
					runAgentBrowserCommand(List.of("get", "url"), workingDirectory, timeoutSeconds, cancelRequested));
			runAgentBrowserCommand(List.of("open", resolveLink(currentUrl, linkUrl)), workingDirectory, timeoutSeconds,
					cancelRequested);
			waitForAgentBrowserLoad(workingDirectory, timeoutSeconds, cancelRequested);
			return;
		}

		String linkText = first(arguments, "link_text", "follow_text");
		int linkIndex = firstInt(arguments, -1, "link_index", "follow_index");
		if (linkText.isBlank() && linkIndex < 0) {
			return;
		}

		JSONArray links = collectAgentBrowserLinks(workingDirectory, Math.max(linkIndex + 1, MAX_LINKS),
				timeoutSeconds, cancelRequested);
		String target = "";
		if (!linkText.isBlank()) {
			String needle = linkText.toLowerCase(Locale.ROOT);
			for (int i = 0; i < links.length(); i++) {
				JSONObject link = links.getJSONObject(i);
				if (link.optString("text").toLowerCase(Locale.ROOT).contains(needle)) {
					target = link.optString("href");
					break;
				}
			}
		} else if (linkIndex < links.length()) {
			target = links.getJSONObject(linkIndex).optString("href");
		}
		if (!target.isBlank()) {
			runAgentBrowserCommand(List.of("open", target), workingDirectory, timeoutSeconds, cancelRequested);
			waitForAgentBrowserLoad(workingDirectory, timeoutSeconds, cancelRequested);
		}
	}

	private static JSONObject collectAgentBrowserPage(File workingDirectory, JSONObject arguments, int timeoutSeconds,
			BooleanSupplier cancelRequested) throws IOException {
		JSONObject result = new JSONObject();
		result.put("status", "ok");
		result.put("url", extractAgentBrowserText(
				runAgentBrowserCommand(List.of("get", "url"), workingDirectory, timeoutSeconds, cancelRequested)));
		result.put("title", extractAgentBrowserText(
				runAgentBrowserCommand(List.of("get", "title"), workingDirectory, timeoutSeconds, cancelRequested)));
		String textScript = "document.body ? document.body.innerText : ''";
		result.put("text", truncate(extractAgentBrowserText(
				runAgentBrowserCommand(List.of("eval", textScript), workingDirectory, timeoutSeconds, cancelRequested)),
				MAX_TEXT_CHARS));
		result.put("links", collectAgentBrowserLinks(workingDirectory,
				firstInt(arguments, MAX_LINKS, "max_links", "links"), timeoutSeconds, cancelRequested));
		return result;
	}

	private static JSONArray collectAgentBrowserLinks(File workingDirectory, int maxLinks, int timeoutSeconds,
			BooleanSupplier cancelRequested) throws IOException {
		int limit = Math.max(0, maxLinks);
		if (limit == 0) {
			return new JSONArray();
		}
		String script = """
				JSON.stringify(Array.from(document.querySelectorAll('a[href]'))
				  .slice(0, %d)
				  .map(link => ({
				    text: (link.innerText || link.textContent || '').trim(),
				    href: link.href || link.getAttribute('href') || ''
				  }))
				  .filter(link => link.href))
				""".formatted(limit);
		String raw = extractAgentBrowserText(
				runAgentBrowserCommand(List.of("eval", script), workingDirectory, timeoutSeconds, cancelRequested));
		try {
			return new JSONArray(raw);
		} catch (Exception e) {
			return new JSONArray();
		}
	}

	private static ToolResult apiAgentBrowser(JSONObject arguments, File workingDirectory,
			BooleanSupplier cancelRequested) throws IOException {
		String url = normalizeUrl(first(arguments, "url", "endpoint"));
		if (url.isBlank()) {
			return ToolResult.output("web: missing url");
		}
		int timeoutSeconds = firstInt(arguments, DEFAULT_TIMEOUT_SECONDS, "timeout_seconds", "timeout");
		try {
			runAgentBrowserCommand(List.of("open", "data:text/html,<title>Elowbe API Test</title>"), workingDirectory,
					timeoutSeconds, cancelRequested);
			if (cancelRequested.getAsBoolean()) {
				return ToolResult.output("web: cancelled");
			}
			String method = first(arguments, "method", "http_method", "verb");
			if (method.isBlank()) {
				method = "GET";
			}
			JSONObject headers = arguments.optJSONObject("headers");
			if (headers == null) {
				headers = new JSONObject();
			}
			String body = first(arguments, "body", "payload", "data");
			String script = """
					(async () => {
					  const response = await fetch(%s, {
					    method: %s,
					    headers: %s,
					    body: %s === "" ? undefined : %s,
					    redirect: "follow"
					  });
					  const text = await response.text();
					  return JSON.stringify({
					    status: "ok",
					    url: response.url,
					    http_status: response.status,
					    status_text: response.statusText,
					    ok: response.ok,
					    headers: Object.fromEntries(response.headers.entries()),
					    body: text
					  });
					})().catch(error => JSON.stringify({ status: "error", error: String(error) }))
					""".formatted(jsString(url), jsString(method.toUpperCase(Locale.ROOT)), headers.toString(),
					jsString(body), jsString(body));
			String raw = extractAgentBrowserText(
					runAgentBrowserCommand(List.of("eval", script), workingDirectory, timeoutSeconds, cancelRequested));
			JSONObject result = new JSONObject(raw);
			if (result.has("body")) {
				result.put("body", truncate(result.optString("body"), MAX_BODY_CHARS));
			}
			return ToolResult.output(result.toString(2));
		} catch (Exception e) {
			return ToolResult.output(new JSONObject()
					.put("status", "error")
					.put("error", e.getMessage())
					.toString(2));
		} finally {
			try {
				runAgentBrowserCommand(List.of("close"), workingDirectory, Math.min(5, Math.max(1, timeoutSeconds)),
						() -> false);
			} catch (IOException ignored) {
			}
		}
	}

	private static String runAgentBrowserCommand(List<String> args, File workingDirectory, int timeoutSeconds,
			BooleanSupplier cancelRequested) throws IOException {
		if (agentBrowserCommand == null || agentBrowserCommand.isBlank()) {
			throw new IOException("agent-browser command is empty");
		}
		if (cancelRequested == null) {
			cancelRequested = () -> false;
		}
		File cwd = workingDirectory == null ? new File(System.getProperty("user.dir")) : workingDirectory;
		if (!cwd.exists()) {
			cwd.mkdirs();
		}
		StringBuilder commandLine = new StringBuilder(agentBrowserCommand.trim());
		for (String arg : args) {
			commandLine.append(' ').append(shellQuote(arg));
		}

		Process process = new ProcessBuilder("/bin/zsh", "-lc", commandLine.toString())
				.directory(cwd)
				.start();
		StreamCollector stdout = new StreamCollector(process.getInputStream());
		StreamCollector stderr = new StreamCollector(process.getErrorStream());
		Thread outThread = new Thread(stdout, "agent-browser-stdout");
		Thread errThread = new Thread(stderr, "agent-browser-stderr");
		outThread.start();
		errThread.start();

		boolean cancelled = false;
		boolean timedOut = false;
		long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, timeoutSeconds)).toNanos();
		try {
			while (true) {
				if (cancelRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
					cancelled = true;
					destroyProcessTree(process);
					break;
				}
				try {
					if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
						break;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					cancelled = true;
					destroyProcessTree(process);
					break;
				}
				if (System.nanoTime() >= deadline) {
					timedOut = true;
					destroyProcessTree(process);
					break;
				}
			}
		} finally {
			if (cancelled || timedOut || process.isAlive()) {
				destroyProcessTree(process);
			}
		}

		try {
			outThread.join();
			errThread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("agent-browser interrupted");
		}

		String stdoutText = stdout.text().trim();
		String stderrText = stderr.text().trim();
		if (cancelled) {
			throw new IOException("agent-browser cancelled");
		}
		if (timedOut) {
			throw new IOException("agent-browser timed out after " + timeoutSeconds + " seconds");
		}
		if (process.exitValue() != 0) {
			String detail = firstNonBlankLine(stderrText);
			if (detail.isBlank()) {
				detail = firstNonBlankLine(stdoutText);
			}
			throw new IOException("agent-browser failed: " + detail);
		}
		return stdoutText;
	}

	private static String extractAgentBrowserText(String output) {
		if (output == null) {
			return "";
		}
		String trimmed = output.trim();
		if (trimmed.isBlank()) {
			return "";
		}
		try {
			JSONObject json = new JSONObject(trimmed);
			if (json.has("data")) {
				Object data = json.get("data");
				if (data instanceof JSONObject object) {
					String value = first(object, "text", "value", "url", "title", "result", "snapshot");
					return value.isBlank() ? object.toString() : value;
				}
				return data == null ? "" : String.valueOf(data);
			}
			String value = first(json, "text", "value", "url", "title", "result", "output", "snapshot");
			return value.isBlank() ? trimmed : value;
		} catch (Exception ignored) {
			return trimmed;
		}
	}

	private static String shellQuote(String value) {
		if (value == null) {
			return "''";
		}
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}

	private static String jsString(String value) {
		return JSONObject.quote(value == null ? "" : value);
	}

	private static void destroyProcessTree(Process process) {
		ProcessHandle handle = process.toHandle();
		handle.descendants().forEach(child -> {
			try {
				child.destroyForcibly();
			} catch (Exception ignored) {
			}
		});
		try {
			handle.destroyForcibly();
		} catch (Exception ignored) {
		}
	}

	private static class StreamCollector implements Runnable {
		private final InputStream input;
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		private StreamCollector(InputStream input) {
			this.input = input;
		}

		@Override
		public void run() {
			try (InputStream stream = input) {
				stream.transferTo(output);
			} catch (IOException e) {
				output.writeBytes(("<stream error: " + e.getMessage() + ">").getBytes(StandardCharsets.UTF_8));
			}
		}

		private String text() {
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	private static ToolResult search(JSONObject arguments, BooleanSupplier cancelRequested) throws IOException {
		String query = first(arguments, "query", "q", "search");
		if (query.isBlank()) {
			return ToolResult.output("web: missing query");
		}
		JSONObject lastResult = null;
		for (SearchProvider provider : SEARCH_PROVIDERS) {
			JSONObject openArgs = new JSONObject(arguments.toString())
					.put("url", provider.searchUrl(query))
					.put("action", "open");
			JSONObject result = loadPage(openArgs, cancelRequested);
			result.put("action", "search");
			result.put("query", query);
			result.put("search_provider", provider.name);
			if (cancelRequested.getAsBoolean()) {
				return ToolResult.output(result.toString(2));
			}
			if (!looksLikeCaptchaBlock(result)) {
				return ToolResult.output(result.toString(2));
			}
			result.put("status", "blocked");
			result.put("warning", "Search provider returned a captcha or bot-check page; trying fallback provider.");
			lastResult = result;
		}
		if (lastResult == null) {
			lastResult = new JSONObject()
					.put("status", "error")
					.put("action", "search")
					.put("query", query)
					.put("error", "No search providers are configured.");
		} else {
			lastResult.put("warning", "All configured search providers returned captcha or bot-check pages.");
		}
		return ToolResult.output(lastResult.toString(2));
	}

	private static ToolResult open(JSONObject arguments, BooleanSupplier cancelRequested) throws IOException {
		String url = normalizeUrl(first(arguments, "url", "href"));
		if (url.isBlank()) {
			return ToolResult.output("web: missing url");
		}
		String action = first(arguments, "action");
		JSONObject result = loadPage(arguments.put("url", url), cancelRequested);
		result.put("action", action.isBlank() ? "open" : action);
		return ToolResult.output(result.toString(2));
	}

	private static JSONObject loadPage(JSONObject arguments, BooleanSupplier cancelRequested) throws IOException {
		Path profileDir = Files.createTempDirectory("elowbe-web-profile-");
		WebDriver driver = null;
		try {
			driver = newChromeDriver(arguments, profileDir);
			if (cancelRequested.getAsBoolean()) {
				return new JSONObject().put("status", "cancelled");
			}

			driver.get(first(arguments, "url"));
			followRequestedLink(driver, arguments);
			if (cancelRequested.getAsBoolean()) {
				return new JSONObject().put("status", "cancelled");
			}

			JSONObject result = new JSONObject();
			result.put("status", "ok");
			result.put("url", driver.getCurrentUrl());
			result.put("title", driver.getTitle());
			result.put("text", truncate(bodyText(driver), MAX_TEXT_CHARS));
			result.put("links", extractLinks(driver, firstInt(arguments, MAX_LINKS, "max_links", "links")));
			return result;
		} finally {
			if (driver != null) {
				driver.quit();
			}
			deleteRecursively(profileDir);
		}
	}

	private static ToolResult api(JSONObject arguments, BooleanSupplier cancelRequested) throws IOException {
		String url = normalizeUrl(first(arguments, "url", "endpoint"));
		if (url.isBlank()) {
			return ToolResult.output("web: missing url");
		}
		Path profileDir = Files.createTempDirectory("elowbe-web-profile-");
		WebDriver driver = null;
		try {
			driver = newChromeDriver(arguments, profileDir);
			driver.get("data:text/html,<title>Elowbe API Test</title>");
			if (cancelRequested.getAsBoolean()) {
				return ToolResult.output("web: cancelled");
			}

			String method = first(arguments, "method", "http_method", "verb");
			if (method.isBlank()) {
				method = "GET";
			}
			JSONObject headers = arguments.optJSONObject("headers");
			if (headers == null) {
				headers = new JSONObject();
			}
			String body = first(arguments, "body", "payload", "data");
			Object response = ((JavascriptExecutor) driver).executeAsyncScript("""
					const url = arguments[0];
					const method = arguments[1];
					const headers = arguments[2];
					const body = arguments[3];
					const done = arguments[4];
					fetch(url, {
					  method,
					  headers,
					  body: body === "" ? undefined : body,
					  redirect: "follow"
					}).then(async response => {
					  const text = await response.text();
					  done(JSON.stringify({
					    status: "ok",
					    url: response.url,
					    http_status: response.status,
					    status_text: response.statusText,
					    ok: response.ok,
					    headers: Object.fromEntries(response.headers.entries()),
					    body: text
					  }));
					}).catch(error => {
					  done(JSON.stringify({ status: "error", error: String(error) }));
					});
					""", url, method.toUpperCase(Locale.ROOT), headers.toMap(), body);
			JSONObject result = new JSONObject(String.valueOf(response));
			if (result.has("body")) {
				result.put("body", truncate(result.optString("body"), MAX_BODY_CHARS));
			}
			return ToolResult.output(result.toString(2));
		} finally {
			if (driver != null) {
				driver.quit();
			}
			deleteRecursively(profileDir);
		}
	}

	private static WebDriver newChromeDriver(JSONObject arguments, Path profileDir) {
		int timeoutSeconds = firstInt(arguments, DEFAULT_TIMEOUT_SECONDS, "timeout_seconds", "timeout");
		ChromeOptions options = new ChromeOptions();
		options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
		options.addArguments("--headless=new");
		options.addArguments("--disable-gpu");
		options.addArguments("--disable-web-security");
		options.addArguments("--allow-running-insecure-content");
		options.addArguments("--no-sandbox");
		options.addArguments("--disable-dev-shm-usage");
		options.addArguments("--window-size=1440,1000");
		options.addArguments("--user-data-dir=" + profileDir.toAbsolutePath());
		WebDriver driver = new ChromeDriver(options);
		driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
		driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
		return driver;
	}

	private static void followRequestedLink(WebDriver driver, JSONObject arguments) {
		String linkUrl = first(arguments, "link_url", "follow_url");
		if (!linkUrl.isBlank()) {
			driver.get(resolveLink(driver.getCurrentUrl(), linkUrl));
			return;
		}
		String linkText = first(arguments, "link_text", "follow_text");
		if (!linkText.isBlank()) {
			for (WebElement link : driver.findElements(By.cssSelector("a[href]"))) {
				try {
					if (link.getText().toLowerCase(Locale.ROOT).contains(linkText.toLowerCase(Locale.ROOT))) {
						String href = link.getAttribute("href");
						if (href != null && !href.isBlank()) {
							driver.get(href);
							return;
						}
					}
				} catch (StaleElementReferenceException ignored) {
				}
			}
		}
		int linkIndex = firstInt(arguments, -1, "link_index", "follow_index");
		if (linkIndex >= 0) {
			JSONArray links = extractLinks(driver, linkIndex + 1);
			if (linkIndex < links.length()) {
				driver.get(links.getJSONObject(linkIndex).optString("href"));
			}
		}
	}

	private static JSONArray extractLinks(WebDriver driver, int maxLinks) {
		int limit = Math.max(0, maxLinks);
		if (limit == 0) {
			return new JSONArray();
		}
		try {
			Object rawLinks = ((JavascriptExecutor) driver).executeScript("""
					return Array.from(document.querySelectorAll('a[href]'))
					  .slice(0, arguments[0])
					  .map(link => ({
					    text: (link.innerText || link.textContent || '').trim(),
					    href: link.href || link.getAttribute('href') || ''
					  }))
					  .filter(link => link.href);
					""", limit);
			return linksFromScriptResult(rawLinks);
		} catch (WebDriverException ignored) {
			return extractLinksWithWebElements(driver, limit);
		}
	}

	private static JSONArray extractLinksWithWebElements(WebDriver driver, int maxLinks) {
		JSONArray links = new JSONArray();
		for (WebElement link : driver.findElements(By.cssSelector("a[href]"))) {
			if (links.length() >= maxLinks) {
				break;
			}
			try {
				String href = link.getAttribute("href");
				if (href == null || href.isBlank()) {
					continue;
				}
				links.put(new JSONObject()
						.put("text", truncate(link.getText().trim(), 200))
						.put("href", href));
			} catch (StaleElementReferenceException ignored) {
			}
		}
		return links;
	}

	private static JSONArray linksFromScriptResult(Object rawLinks) {
		JSONArray links = new JSONArray();
		if (!(rawLinks instanceof List<?> rawList)) {
			return links;
		}
		for (Object rawLink : rawList) {
			if (!(rawLink instanceof Map<?, ?> rawMap)) {
				continue;
			}
			Object rawHref = rawMap.get("href");
			String href = rawHref == null ? "" : String.valueOf(rawHref);
			if (href.isBlank()) {
				continue;
			}
			Object rawText = rawMap.get("text");
			String text = rawText == null ? "" : String.valueOf(rawText);
			links.put(new JSONObject()
					.put("text", truncate(text.trim(), 200))
					.put("href", href));
		}
		return links;
	}

	private static String bodyText(WebDriver driver) {
		try {
			return driver.findElement(By.tagName("body")).getText();
		} catch (Exception e) {
			return "";
		}
	}

	private static String normalizeUrl(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		String trimmed = url.trim();
		if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
			return trimmed;
		}
		return "https://" + trimmed;
	}

	private static String resolveLink(String baseUrl, String linkUrl) {
		if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://") || linkUrl.startsWith("data:")) {
			return linkUrl;
		}
		try {
			return URI.create(baseUrl).resolve(linkUrl).toString();
		} catch (IllegalArgumentException e) {
			return normalizeUrl(linkUrl);
		}
	}

	private static boolean looksLikeCaptchaBlock(JSONObject result) {
		String haystack = (result.optString("url") + "\n"
				+ result.optString("title") + "\n"
				+ result.optString("text")).toLowerCase(Locale.ROOT);
		return haystack.contains("captcha")
				|| haystack.contains("verify you are human")
				|| haystack.contains("human verification")
				|| haystack.contains("are you a robot")
				|| haystack.contains("not a robot")
				|| haystack.contains("automated requests")
				|| haystack.contains("automated queries")
				|| haystack.contains("unusual traffic")
				|| haystack.contains("detected unusual")
				|| haystack.contains("complete the challenge")
				|| haystack.contains("security check");
	}

	private static String first(JSONObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				return String.valueOf(object.get(key));
			}
		}
		return "";
	}

	private static int firstInt(JSONObject object, int defaultValue, String... keys) {
		for (String key : keys) {
			if (object.has(key) && !object.isNull(key)) {
				Object raw = object.get(key);
				if (raw instanceof Number number) {
					return number.intValue();
				}
				try {
					return Integer.parseInt(String.valueOf(raw).trim());
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return defaultValue;
	}

	private static String firstNonBlankLine(String text) {
		for (String line : text.split("\n")) {
			if (!line.isBlank()) {
				return line.trim();
			}
		}
		return "";
	}

	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) {
			return text == null ? "" : text;
		}
		return text.substring(0, Math.max(0, maxChars)) + "\n... truncated ...";
	}

	private static String truncateInline(String text, int maxChars) {
		return truncate(text == null ? "" : text.replace('\n', ' ').trim(), maxChars);
	}

	private static void deleteRecursively(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private record SearchProvider(String name, String urlPrefix) {
		String searchUrl(String query) {
			return urlPrefix + URLEncoder.encode(query, StandardCharsets.UTF_8);
		}
	}
}
