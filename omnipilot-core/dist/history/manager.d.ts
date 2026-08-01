import { ChatSession, ChatSessionSummary, ChatHistoryStore } from './models.js';
export declare class HistoryManager {
    private storageDir;
    private filePath;
    constructor(storageDir?: string);
    private ensureDirectoryExists;
    loadStore(): ChatHistoryStore;
    saveStore(store: ChatHistoryStore): void;
    getSessions(): ChatSessionSummary[];
    getSession(id: string): ChatSession | null;
    saveSession(session: ChatSession): void;
    deleteSession(id: string): void;
    clearAll(): void;
}
