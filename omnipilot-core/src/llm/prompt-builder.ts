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
      basePrompt += `Mode: AGENT.\nYou have access to autonomous tools to inspect files, make edits, and execute terminal commands.\nAlways inspect files before editing and verify your work.\n`;
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
