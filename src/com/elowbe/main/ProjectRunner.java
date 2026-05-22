package com.elowbe.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs run.sh or run.bat from a project directory.
 */
public final class ProjectRunner {
	public static final class RunCancelledException extends IOException {
		private RunCancelledException() {
			super("Run cancelled");
		}
	}

	private static final AtomicReference<Process> ACTIVE_RUN = new AtomicReference<>();

	private ProjectRunner() {
	}

	public static void cancel() {
		Process process = ACTIVE_RUN.get();
		if (process != null) {
			destroyProcessTree(process);
		}
	}

	public static void run(File directory, Consumer<String> outputLine) throws IOException, InterruptedException {
		run(directory, outputLine, () -> false);
	}

	public static void run(File directory, Consumer<String> outputLine, BooleanSupplier cancelRequested)
			throws IOException, InterruptedException {
		if (directory == null || !directory.isDirectory()) {
			throw new IOException("Not a directory: " + (directory == null ? "(null)" : directory.getPath()));
		}
		if (cancelRequested == null) {
			cancelRequested = () -> false;
		}

		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		File script = new File(directory, windows ? "run.bat" : "run.sh");
		if (!script.isFile()) {
			throw new IOException("Missing " + script.getName() + " in " + directory.getPath());
		}

		if (!windows) {
			ensureExecutable(script);
		}

		String runCommand = windows ? "cmd /c run.bat" : "bash ./run.sh";
		String shell = new File("/bin/zsh").canExecute() ? "/bin/zsh" : "/bin/bash";
		List<String> command = List.of(shell, "-lc", runCommand);

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(directory);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		ACTIVE_RUN.set(process);

		boolean cancelled = false;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			while (true) {
				if (cancelRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
					cancelled = true;
					destroyProcessTree(process);
					break;
				}
				if (reader.ready()) {
					String line = reader.readLine();
					if (line == null) {
						break;
					}
					outputLine.accept(line);
					continue;
				}
				if (!process.isAlive()) {
					while (reader.ready()) {
						String line = reader.readLine();
						if (line == null) {
							break;
						}
						outputLine.accept(line);
					}
					break;
				}
				Thread.sleep(50);
			}
		} finally {
			ACTIVE_RUN.compareAndSet(process, null);
		}

		if (cancelled || cancelRequested.getAsBoolean()) {
			throw new RunCancelledException();
		}

		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IOException(script.getName() + " exited with code " + exitCode);
		}
	}

	private static void ensureExecutable(File script) throws IOException, InterruptedException {
		if (script.canExecute()) {
			return;
		}
		if (script.setExecutable(true, false) && script.canExecute()) {
			return;
		}
		Process process = new ProcessBuilder("chmod", "+x", script.getAbsolutePath()).start();
		int exitCode = process.waitFor();
		if (exitCode != 0 || !script.canExecute()) {
			throw new IOException("Could not make run.sh executable: " + script.getPath());
		}
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
}
