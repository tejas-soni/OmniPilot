import { ChatMessage } from './models.js';

export type AgentMode = 'READ-ONLY' | 'CHAT' | 'AGENT';

export interface PromptContext {
  mode: AgentMode;
  osInfo: string;
  editorContext?: {
    file?: string;
    content?: string;
    selectedText?: string;
    language?: string;
  };
}

export class PromptBuilder {
  public static buildSystemPrompt(context: PromptContext): string {
    const { mode, osInfo, editorContext } = context;

    let basePrompt = `You are OmniPilot, an advanced AI coding assistant embedded in the IDE.\nOperating System: ${osInfo}.\n`;

    if (mode === 'READ-ONLY') {
      basePrompt += `Mode: READ-ONLY.\nYou must ONLY analyze and explain code. Do NOT invoke any file editing or command execution tools.\n`;
    } else if (mode === 'CHAT') {
      basePrompt += `Mode: CHAT.\nProvide clear, helpful coding guidance, suggestions, and explanations.\n`;
     } else {
       basePrompt += `Mode: AGENT.
You have access to autonomous tools. Use them by calling the provided functions; do NOT write tool calls as text.

Available tools:
- read_file(filePath): Read a file. Use the exact absolute path the user gave (e.g. E:\\Tejas\\Plugins\\cline\\package.json).
- write_file(filePath, content): Create or overwrite a file.
- run_command(command): Run a shell command. It runs in the workspace root; each call is a fresh shell, so 'cd' does NOT persist. Prefer full paths in a single command (e.g. dir "E:\\Tejas\\Plugins\\cline" /b) instead of cd.

How to explore a project:
1. To list a directory, call run_command with: dir "<absolute path>" /b   (Windows) or ls "<absolute path>"   (Unix).
2. To read a specific file, call read_file with its absolute path.
3. To analyze a project, read its key files (package.json, build files, README, main sources) rather than relying only on commands.

Important:
- Always use the exact absolute paths the user provides; do not claim a path is invalid before actually trying it with a tool.
- If one tool call fails, try a different tool or path instead of giving up.
- Always inspect files before editing and verify your work.
`;
     }


    if (editorContext) {
      basePrompt += `\n--- CURRENT EDITOR CONTEXT ---\n`;
      if (editorContext.file) {
        basePrompt += `Active File: ${editorContext.file} (${editorContext.language || 'unknown'})\n`;
      }
      if (editorContext.selectedText) {
        basePrompt += `Selected Code:\n\`\`\`\n${editorContext.selectedText}\n\`\`\`\n`;
      } else if (editorContext.content) {
        basePrompt += `Document Content (Truncated if long):\n\`\`\`\n${editorContext.content.substring(0, 4000)}\n\`\`\`\n`;
      }
    }

    return basePrompt;
  }

  public static normalizeMessagesForClaude(messages: ChatMessage[], systemPrompt: string): ChatMessage[] {
    // For models like Claude that require a single system prompt or specific message ordering
    const result: ChatMessage[] = [];
    let systemAdded = false;

    for (const msg of messages) {
      if (msg.role === 'system') {
        if (!systemAdded) {
          result.push({ role: 'system', content: systemPrompt + '\n' + (msg.content || '') });
          systemAdded = true;
        }
      } else {
        result.push(msg);
      }
    }

    if (!systemAdded) {
      result.unshift({ role: 'system', content: systemPrompt });
    }

    return result;
  }
}
