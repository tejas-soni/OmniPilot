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
export declare class PromptBuilder {
    static buildSystemPrompt(context: PromptContext): string;
    static normalizeMessagesForClaude(messages: ChatMessage[], systemPrompt: string): ChatMessage[];
}
