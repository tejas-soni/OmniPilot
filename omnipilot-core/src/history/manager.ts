import * as fs from 'fs';
import * as path from 'path';
import { ChatSession, ChatSessionSummary, ChatHistoryStore } from './models.js';

export class HistoryManager {
  private storageDir: string;
  private filePath: string;

  constructor(storageDir?: string) {
    this.storageDir = storageDir || path.join(process.env.HOME || process.env.USERPROFILE || '.', '.omnipilot');
    this.filePath = path.join(this.storageDir, 'omnipilot_chat_history.json');
    this.ensureDirectoryExists();
  }

  private ensureDirectoryExists(): void {
    if (!fs.existsSync(this.storageDir)) {
      fs.mkdirSync(this.storageDir, { recursive: true });
    }
  }

  public loadStore(): ChatHistoryStore {
    try {
      if (fs.existsSync(this.filePath)) {
        const data = fs.readFileSync(this.filePath, 'utf8');
        const parsed = JSON.parse(data);
        return { sessions: Array.isArray(parsed.sessions) ? parsed.sessions : [] };
      }
    } catch (e) {
      // Fallback on corrupt JSON file
    }
    return { sessions: [] };
  }

  public saveStore(store: ChatHistoryStore): void {
    this.ensureDirectoryExists();
    fs.writeFileSync(this.filePath, JSON.stringify(store, null, 2), 'utf8');
  }

  public getSessions(): ChatSessionSummary[] {
    const store = this.loadStore();
    return store.sessions
      .sort((a, b) => b.timestamp - a.timestamp)
      .map((s) => ({
        id: s.id,
        title: s.title,
        timestamp: s.timestamp
      }));
  }

  public getSession(id: string): ChatSession | null {
    const store = this.loadStore();
    return store.sessions.find((s) => s.id === id) || null;
  }

  public saveSession(session: ChatSession): void {
    const store = this.loadStore();
    const existingIndex = store.sessions.findIndex((s) => s.id === session.id);

    if (existingIndex >= 0) {
      store.sessions[existingIndex] = session;
    } else {
      store.sessions.push(session);
    }

    this.saveStore(store);
  }

  public deleteSession(id: string): void {
    const store = this.loadStore();
    store.sessions = store.sessions.filter((s) => s.id !== id);
    this.saveStore(store);
  }

  public clearAll(): void {
    this.saveStore({ sessions: [] });
  }
}
