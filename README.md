# ElowbeAgent

ElowbeAgent is a desktop coding agent with a terminal-style UI. It helps you build and edit software projects using local or remote LLMs, with built-in tools for reading and writing files, running shell commands, Maven project management, web browsing, and Git review.

The agent defaults to **Java + Maven** for new projects and can auto-select **Cursor-style skills** from your project or install directory.

---

## Features

- **Interactive console UI** — chat with the agent, review tool output, and manage projects in one window
- **Multi-provider LLM support** — Ollama, LM Studio (`lmstudio:` prefix), and Claude (`claude:` prefix)
- **Agent tools** — `read`, `write`, `edit`, `bash`, `run`, `maven`, `web`, and optional `subtask` delegation
- **Project workspace** — create, open, and switch between projects under a configurable projects folder
- **Git workflow** — auto-init repos, interactive diff review, and commit from `/review`
- **Skills** — load `skill.md` / `SKILL.md` supplements from projects and `.cursor/skills/`
- **Image input** — drag and drop photos into the window to attach them to the next message
- **Persistent settings** — model, LLM URLs, web backend, and preferences saved to `~/.elowbe-agent/settings.json`

---

## Requirements

| Requirement | Notes |
|-------------|-------|
| **Java 21+** | Required to build and run |
| **Maven** | Used by the agent and for Java project builds |
| **Git** | Used for project repos and `/review` |
| **LLM backend** | At least one of Ollama, LM Studio, or Claude API access |
| **Eclipse workspace deps** | **JinConsole** and **JInteractive** (sibling Eclipse projects in your workspace) |
| **json-java** | `lib/json-java.jar` on the classpath |
| **agent-browser** *(recommended)* | [Vercel agent-browser](https://github.com/vercel-labs/agent-browser) CLI for the web tool (default backend) |
| **Chrome + ChromeDriver** *(optional)* | Fallback when Selenium web backend is enabled |
| **zsh or bash** | Shell commands run through a managed zsh/bash wrapper on macOS/Linux |

---

## Installation

### 1. Clone the repository

```bash
git clone <repository-url> ElowbeAgent
cd ElowbeAgent
```

### 2. Set up Eclipse dependencies

ElowbeAgent is an Eclipse/Maven project that depends on two sibling projects:

1. Import **JinConsole** and **JInteractive** into the same Eclipse workspace as ElowbeAgent
2. Ensure `lib/json-java.jar` exists (place the JAR in the `lib/` folder if it is not already present)
3. Open the project in Eclipse — the `.classpath` wires JinConsole, JInteractive, and Maven dependencies

### 3. Build with Maven

From the project root:

```bash
mvn compile
```

Maven resolves **Selenium** from `pom.xml`. JinConsole and JInteractive must still be available on the Eclipse classpath (or equivalent) to run the main class.

### 4. Run the agent

**From Eclipse:** Run `com.elowbe.main.ElowbeAgent` as a Java application.

**From the command line** (after compiling with all dependencies on the classpath):

```bash
java com.elowbe.main.ElowbeAgent
```

On startup, ElowbeAgent opens a **960×720** window with a scrollable output area and command input.

---

## Setting up services

Configure these before sending your first agent message.

### Ollama

1. [Install Ollama](https://ollama.com/)
2. Start the server (default: `http://localhost:11434`)
3. Pull a model, for example:

   ```bash
   ollama pull qwen2.5-coder:32b
   ```

4. In ElowbeAgent:

   ```
   /llm -ollama http://localhost:11434
   /model
   ```

   Select an Ollama model from the picker (no prefix needed for Ollama models).

### LM Studio

1. [Install LM Studio](https://lmstudio.ai/)
2. Load a model and start the **Local Server** (OpenAI-compatible API, default port **1234**)
3. In ElowbeAgent:

   ```
   /llm -lmstudio http://localhost:1234/v1
   /model
   ```

   Choose a model prefixed with `lmstudio:`, for example `lmstudio:qwen3.6-27b-mtp`.

### Claude

Select a model prefixed with `claude:` from `/model` when your environment is configured for Claude API access through JinConsole’s LLM layer.

### Web tool — agent-browser (default)

The web tool uses [Vercel agent-browser](https://github.com/vercel-labs/agent-browser) by default:

```bash
npm install -g agent-browser
agent-browser install   # if required by your platform
```

Verify and configure in the agent:

```
/web -show
/web -command "agent-browser --json"
```

### Web tool — Selenium fallback

If you prefer Selenium instead:

```
/web -selenium
```

Install **Google Chrome** and ensure **ChromeDriver** is available on your `PATH`.

### Check current configuration

```
/llm -show
/web -show
/model
```

Settings are persisted automatically to `~/.elowbe-agent/settings.json`.

---

## Quick start

1. **Start ElowbeAgent** and point it at your LLM backend (see above).
2. **Create a project:**

   ```
   /new my-app
   ```

   Projects are created under `~/Documents/elowbe-projects` by default.

3. **Open an existing project:**

   ```
   /open
   ```

4. **Send a task** in plain text, for example:

   ```
   Create a Spring Boot REST API with a /health endpoint
   ```

5. **Review and commit changes:**

   ```
   /review
   ```

6. **Run the project** (requires `run.sh` or `run.bat` in the project directory):

   ```
   /run
   ```

---

## Usage

### Sending messages

- **Plain text** — sent to the agent as a coding task
- **While the agent is busy** — plain text becomes an **interjection** (stops the current command and adds guidance)
- **Drag and drop** — attach `.jpg`, `.jpeg`, `.png`, `.gif`, `.webp`, or `.bmp` images to the next message
- **Cancel** — `Ctrl+Shift+C` (use `Cmd+Shift+C` on macOS) cancels the running agent or `/run` process

### Terminal commands

These run locally without invoking the LLM:

| Command | Description |
|---------|-------------|
| `cd [path]` | Change working directory (`~` for home) |
| `ls [path]` | List directory contents |
| `mkdir [-p] dir` | Create a directory |

The current directory is shown in the status bar at the bottom of the window.

### Slash commands

All configuration and workflow commands start with `/`.

| Command | Description |
|---------|-------------|
| `/help -commands` | List all commands |
| `/clear` | Clear chat history and token counters |
| `/model` | Open the model picker (Ollama, LM Studio, Claude) |
| `/llm` | Configure Ollama and LM Studio base URLs |
| `/thinking` | Enable or disable reasoning/thinking output |
| `/subtasks` | Enable or disable worker subtask delegation |
| `/web` | Configure web tool backend (agent-browser or Selenium) |
| `/context` | Set LLM context window size (`0` = server default) |
| `/output` | Set max tokens per LLM response (`0` = server default) |
| `/history` | Show persisted chat history size |
| `/system` | View, edit, or reload the system prompt |
| `/skill` | Manage project and discovered skills |
| `/review` | Review agent file changes and commit |
| `/run` | Execute `run.sh` or `run.bat` in the current project |
| `/new` | Create a new project |
| `/open` | Switch to a project in the projects folder |

#### Examples

```
/llm -ollama http://localhost:11434
/llm -lmstudio http://localhost:1234/v1
/llm -show

/thinking -off
/subtasks -on
/web -agent-browser on
/context -set 32768
/output -set 64000

/system -reload
/system -show

/skill -list
/skill -select
/skill -set my-skill,other-skill
/skill -clear

/new my-project
/new -dir ~/dev -name my-project
/open
/review
/run
```

---

## Projects

ElowbeAgent manages coding work inside **project directories** rather than the projects root itself.

| Setting | Default |
|---------|---------|
| Projects folder | `~/Documents/elowbe-projects` |
| Settings file | `~/.elowbe-agent/settings.json` |

- Use `/new project-name` to create a project (Git repo initialized automatically)
- Use `/open` to switch between projects
- Use `/new -dir /path/to/parent -name project-name` to create a project elsewhere and remember that parent as the projects folder
- Agent tasks, `/review`, and `/run` require an open project — not the projects root directory

Each project can include:

- **`run.sh`** / **`run.bat`** — executed by `/run`
- **`skill.md`** or **`SKILL.md`** — primary skill supplement for that project
- **`.cursor/skills/`** or **`skills/`** — additional discoverable skills

---

## Skills

Skills are markdown files with optional YAML frontmatter (`name`, `description`) and a body of agent instructions — similar to Cursor Agent Skills.

**Discovery locations:**

- Project: `skill.md`, `SKILL.md`, `.cursor/skills/*/SKILL.md`, `skills/*/SKILL.md`
- ElowbeAgent install: `skills/`, `src/skills/`, `.cursor/skills/`

**Selection:**

- **Automatic** — the LLM picks relevant skills for each task (default)
- **Manual** — `/skill -select` or `/skill -set id1,id2` (skips auto-selection)

```
/skill -list
/skill -show
/skill -reload
/skill -clear
```

---

## Agent tools

The agent uses a structured tool loop (see `src/system.txt`). Available tools:

| Tool | Purpose |
|------|---------|
| `read` | Read files with line numbers |
| `write` | Create or overwrite files (not for `pom.xml`) |
| `edit` | Replace a line range in a file |
| `bash` | Run shell commands (not for Maven or web tasks) |
| `run` | Run `run.sh` / `run.bat` in the project |
| `maven` | Init, configure, and build Maven projects |
| `web` | Browse, search, and test HTTP APIs in a browser context |
| `subtask` | Delegate a single atomic action to a worker (when subtasks enabled) |
| `done` | Signal task completion |

**Defaults for new work:**

- Java + Maven unless you explicitly request another stack
- Spring Boot for web apps, Swing for desktop GUIs
- Maven tool (not raw `mvn` or hand-edited `pom.xml`) for all POM changes

---

## System prompts

Prompt files live under `src/`:

| File | Used when |
|------|-----------|
| `system.txt` | Base system prompt (always) |
| `system-direct.txt` | Direct mode (subtasks **off**, default) |
| `system-subtasks.txt` | Subtask delegation mode (subtasks **on**) |

Reload after editing:

```
/system -reload
/subtasks -show
```

---

## Configuration reference

Settings are stored in **`~/.elowbe-agent/settings.json`**:

```json
{
  "projectsDirectory": "/Users/you/Documents/elowbe-projects",
  "agentModel": "lmstudio:qwen3.6-27b-mtp",
  "ollamaUrl": "http://localhost:11434",
  "lmstudioUrl": "http://localhost:1234/v1",
  "thinkingEnabled": true,
  "subtasksEnabled": false,
  "agentBrowserWebEnabled": true,
  "agentBrowserCommand": "agent-browser --json",
  "agentContextLength": 0,
  "agentMaxOutputTokens": 32000,
  "manualSkillIds": []
}
```

| Field | Description |
|-------|-------------|
| `projectsDirectory` | Root folder for `/new` and `/open` |
| `agentModel` | Active model (`lmstudio:...`, `claude:...`, or plain Ollama name) |
| `ollamaUrl` | Ollama API base URL |
| `lmstudioUrl` | LM Studio OpenAI-compatible API base URL |
| `thinkingEnabled` | Show model reasoning tokens in the UI |
| `subtasksEnabled` | Enable worker subtask delegation |
| `agentBrowserWebEnabled` | Use agent-browser CLI instead of Selenium |
| `agentBrowserCommand` | Base command for agent-browser |
| `agentContextLength` | Context window override (`0` = server default) |
| `agentMaxOutputTokens` | Max generated tokens per response |
| `manualSkillIds` | Fixed skill selection (empty = auto) |

Most values can also be changed at runtime with slash commands; changes are saved automatically.

---

## Git workflow

When you work inside a project (not the projects root):

1. ElowbeAgent **auto-initializes** a Git repository if one does not exist
2. After a task completes, run **`/review`**
3. Accept or reject each change in the diff viewer
4. Enter a commit subject and optional description
5. Accepted changes are staged and committed

---

## Project structure

```
ElowbeAgent/
├── src/
│   ├── com/elowbe/
│   │   ├── agent/          # Agent loop and tool orchestration
│   │   ├── commands/       # Slash command parsing
│   │   ├── git/            # Git init, diff, commit
│   │   ├── main/           # UI, settings, skills, project management
│   │   └── tools/          # read, write, bash, maven, web, etc.
│   ├── system.txt          # Base agent system prompt
│   ├── system-direct.txt   # Direct-work mode supplement
│   └── system-subtasks.txt # Subtask mode supplement
├── res/                    # Fonts and assets
├── lib/                    # json-java.jar (classpath)
└── pom.xml
```

---

## Troubleshooting

| Issue | What to try |
|-------|-------------|
| **No models in `/model`** | Confirm Ollama or LM Studio is running; check `/llm -show` URLs |
| **Web tool fails** | Run `/web -show`; test `agent-browser --json` in a terminal, or switch to `/web -selenium` |
| **Maven commands fail** | Ensure `mvn` is on your `PATH` and Java 21 is active |
| **Skills not found** | Run `/skill -list`; check skill paths under `.cursor/skills/` or `skills/` |
| **Cannot review or run in projects folder** | Use `/open` or `/new` to enter a project directory first |
| **Settings not applied** | Inspect `~/.elowbe-agent/settings.json`; restart ElowbeAgent after manual edits |

---

## License

See the repository license file for details.
