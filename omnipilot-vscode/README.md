# OmniPilot AI — Bring Your Own Model for VS Code

**OmniPilot** is a powerful AI coding assistant for Visual Studio Code that allows you to connect **any OpenAI-compatible API** (Groq, OpenAI, Ollama, NVIDIA NIM, Anthropic proxies, LM Studio, vLLM, LocalAI, and more) with 100% control over your credentials and privacy.

---

## ✨ Key Features

- **Bring Your Own Model (BYOM)**: Connect Groq (Llama 3.3, Qwen 2.5), OpenAI (GPT-4o), Ollama, NVIDIA NIM, or custom local/cloud endpoints.
- **Dynamic Model Discovery**: Click `↺` to automatically fetch all available models directly from your provider API endpoint.
- **JetBrains Parity**: Dedicated Settings Panel UI (`Tools › OmniPilot`) to manage providers, API keys, active models, and feature toggles.
- **Full History Persistence**: Chat history saved automatically across VS Code sessions with date grouping and quick restore.
- **Thought Process Blocks**: Collapsible `<think>` reasoning visualization for reasoning models (DeepSeek R1, Qwen 2.5 Coder, etc.).
- **Inline Completions (Ghost Text)**: AI code completion as you type.
- **Agent Mode & Auto-Approve**: Auto-approve file edits for agentic workflows.
- **MCP Integration**: Connect Model Context Protocol (MCP) server endpoints for external tools.

---

## 🚀 Getting Started

1. **Install OmniPilot** from the VS Code Marketplace or from VSIX.
2. Open the OmniPilot sidebar from the **Activity Bar** (OmniPilot icon).
3. Click **Open Settings →** or the `⚙` icon in the chat header.
4. Click **Add** to create a new provider:
   - **Name**: `Groq` (or any provider name)
   - **API Base URL**: `https://api.groq.com/openai/v1`
   - **API Key**: `gsk_...`
5. Click `↺` to fetch available models, or click `+` to add model names manually.
6. Check the models you want to enable, click **Save**, and start chatting!

---

## ⌨️ Commands

| Command | Description |
|---|---|
| `OmniPilot: New Chat` | Start a fresh conversation session |
| `OmniPilot: Open Settings` | Open the OmniPilot Provider & Settings Manager panel |

---

## 🛡️ Privacy & Security

OmniPilot communicates **directly** with your configured LLM endpoints. Your API keys are saved locally in your VS Code workspace settings. No intermediate telemetry or third-party tracking.

---

## 📄 License

[MIT License](LICENSE.txt) © Tejas Soni
