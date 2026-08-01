"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.HistoryManager = void 0;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
class HistoryManager {
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
exports.HistoryManager = HistoryManager;
