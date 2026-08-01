import { ChatMessage } from '../llm/models.js';

export interface ChatSession {
  id: string;
  title: string;
  timestamp: number;
  messages: ChatMessage[];
}

export interface ChatSessionSummary {
  id: string;
  title: string;
  timestamp: number;
}

export interface ChatHistoryStore {
  sessions: ChatSession[];
}
