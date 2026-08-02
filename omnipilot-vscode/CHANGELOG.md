# Change Log

All notable changes to the OmniPilot VS Code extension will be documented in this file.

## [1.1.0] - 2026-08-02

### Added
- Complete JetBrains-parity Provider & Settings Management UI (`resources/settings-webview.html`).
- Dynamic model fetch (`↺`) directly from OpenAI-compatible `/models` endpoints.
- Chat history persistence to disk (`~/.omnipilot/omnipilot_chat_history.json`) with session restore and deletion.
- Monochromatic Activity Bar icon and Status Bar integration (`$(hubot)` status indicator).
- Bundled `omnipilot-server.js` JSON-RPC engine in VSIX package.
- Support for Inline Completions (Ghost Text), Agent Mode auto-approval, and MCP server URL.
