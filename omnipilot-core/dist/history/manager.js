import * as fs from 'fs';
import * as path from 'path';
export class HistoryManager {
    storageDir;
    filePath;
    constructor(storageDir) {
        this.storageDir = storageDir || path.join(process.env.HOME || process.env.USERPROFILE || '.', '.omnipilot');
        this.filePath = path.join(this.storageDir, 'omnipilot_chat_history.json');
        this.ensureDirectoryExists();
    }
    ensureDirectoryExists() {
        if (!fs.existsSync(this.storageDir)) {
            fs.mkdirSync(this.storageDir, { recursive: true });
        }
    }
    loadStore() {
        try {
            if (fs.existsSync(this.filePath)) {
                const data = fs.readFileSync(this.filePath, 'utf8');
                const parsed = JSON.parse(data);
                return { sessions: Array.isArray(parsed.sessions) ? parsed.sessions : [] };
            }
        }
        catch (e) {
            // Fallback on corrupt JSON file
        }
        return { sessions: [] };
    }
    saveStore(store) {
        this.ensureDirectoryExists();
        fs.writeFileSync(this.filePath, JSON.stringify(store, null, 2), 'utf8');
    }
    getSessions() {
        const store = this.loadStore();
        return store.sessions
            .sort((a, b) => b.timestamp - a.timestamp)
            .map((s) => ({
            id: s.id,
            title: s.title,
            timestamp: s.timestamp
        }));
    }
    getSession(id) {
        const store = this.loadStore();
        return store.sessions.find((s) => s.id === id) || null;
    }
    saveSession(session) {
        const store = this.loadStore();
        const existingIndex = store.sessions.findIndex((s) => s.id === session.id);
        if (existingIndex >= 0) {
            store.sessions[existingIndex] = session;
        }
        else {
            store.sessions.push(session);
        }
        this.saveStore(store);
    }
    deleteSession(id) {
        const store = this.loadStore();
        store.sessions = store.sessions.filter((s) => s.id !== id);
        this.saveStore(store);
    }
    clearAll() {
        this.saveStore({ sessions: [] });
    }
}
