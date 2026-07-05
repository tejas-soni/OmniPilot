# OmniPilot

OmniPilot is an advanced, fully autonomous AI coding assistant plugin for JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, etc.). It brings the power of state-of-sart Large Language Models directly into your editor with deep IDE integration.

## Features

- **Multi-Provider Support**: Seamlessly connect to any OpenAI-compatible API, including OpenAI, Groq, Anthropic, NVIDIA NIM, Cloudflare, and local models via LM Studio or Ollama.
- **Dynamic Model Fetching**: Automatically pull the latest available models from your configured provider directly within the IDE settings.
- **Agent Mode (Auto-Pilot)**: Give OmniPilot a complex task and watch it autonomously execute terminal commands, read project files, and write code on your behalf.
- **Granular Permissions System**: Full control over what the Agent can do. Review every file write via a rich Diff Viewer and approve or deny terminal commands before they execute.
- **Smart Context**: Automatically attaches your currently focused file and selected text to the context window.
- **Chat Mode**: Standard conversational AI mode with markdown rendering, syntax highlighting, and 1-click buttons to copy code or insert it directly at your cursor.
- **Persistent State**: Your chat history, selected models, and configured providers are safely persisted across IDE restarts.

## Installation

You can build and run this plugin locally or install it via the JetBrains Marketplace (coming soon).

### Building Locally
1. Clone the repository.
2. Open the project in IntelliJ IDEA.
3. Run the Gradle `buildPlugin` task.
4. The generated ZIP file will be located in `build/distributions/`. You can install this ZIP manually in any JetBrains IDE via **Settings > Plugins > ⚙️ > Install Plugin from Disk...**

### Running in Sandbox
To test the plugin in a sandboxed IDE instance:
```bash
./gradlew runIde
```

## Configuration

1. Navigate to **Settings > Tools > OmniPilot**.
2. Click **Add** to create a new provider configuration.
3. Enter your **Base URL** (e.g., `https://api.groq.com/openai/v1`) and **API Key**.
4. Click the **Refresh Icon** in the Models toolbar to dynamically fetch and select the models you wish to use.
5. Click **Apply** and start chatting!

## Modes

- **Chat (Ask)**: Ask questions, get explanations, and request code snippets. OmniPilot can read your files if you ask it to, but it will only provide code blocks in the chat window.
- **Agent (Auto)**: OmniPilot becomes fully autonomous. It can read files, write files, and run terminal commands to accomplish your goal.
- **Read-Only**: Strict mode where OmniPilot is blocked from executing any writes or terminal commands.

## Requirements
- JetBrains IDE (2023.2 or newer)
- JCEF (Chromium Embedded Framework) support enabled in your IDE runtime.

## License
MIT License
