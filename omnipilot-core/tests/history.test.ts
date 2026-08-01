import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { HistoryManager } from '../src/history/manager.js';

describe('HistoryManager Unit Tests', () => {
  let tempDir: string;
  let historyManager: HistoryManager;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'omnipilot-history-test-'));
    historyManager = new HistoryManager(tempDir);
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  test('should return empty list initially', () => {
    expect(historyManager.getSessions()).toEqual([]);
  });

  test('should execute ensureDirectoryExists when dir already exists', () => {
    const manager2 = new HistoryManager(tempDir);
    expect(manager2.getSessions()).toEqual([]);
  });

  test('should save and retrieve session', () => {
    const session = {
      id: 'sess-1',
      title: 'First Chat',
      timestamp: 1000,
      messages: [{ role: 'user' as const, content: 'Hello' }]
    };

    historyManager.saveSession(session);

    const summaries = historyManager.getSessions();
    expect(summaries).toHaveLength(1);
    expect(summaries[0]).toEqual({
      id: 'sess-1',
      title: 'First Chat',
      timestamp: 1000
    });

    const loaded = historyManager.getSession('sess-1');
    expect(loaded).toEqual(session);
  });

  test('should update existing session', () => {
    const session1 = {
      id: 'sess-1',
      title: 'First Chat',
      timestamp: 1000,
      messages: [{ role: 'user' as const, content: 'Hello' }]
    };
    historyManager.saveSession(session1);

    const session1Updated = {
      ...session1,
      title: 'Updated Chat',
      messages: [{ role: 'user' as const, content: 'Hello' }, { role: 'assistant' as const, content: 'Hi!' }]
    };
    historyManager.saveSession(session1Updated);

    const loaded = historyManager.getSession('sess-1');
    expect(loaded?.title).toBe('Updated Chat');
    expect(loaded?.messages).toHaveLength(2);
  });

  test('should return null for non-existing session', () => {
    expect(historyManager.getSession('non-existent')).toBeNull();
  });

  test('should delete session correctly', () => {
    historyManager.saveSession({ id: 's1', title: 'S1', timestamp: 1, messages: [] });
    historyManager.saveSession({ id: 's2', title: 'S2', timestamp: 2, messages: [] });

    expect(historyManager.getSessions()).toHaveLength(2);

    historyManager.deleteSession('s1');
    const remaining = historyManager.getSessions();
    expect(remaining).toHaveLength(1);
    expect(remaining[0].id).toBe('s2');
  });

  test('should clear all sessions', () => {
    historyManager.saveSession({ id: 's1', title: 'S1', timestamp: 1, messages: [] });
    historyManager.saveSession({ id: 's2', title: 'S2', timestamp: 2, messages: [] });

    historyManager.clearAll();
    expect(historyManager.getSessions()).toEqual([]);
  });

  test('should handle corrupt JSON file gracefully', () => {
    const filePath = path.join(tempDir, 'omnipilot_chat_history.json');
    fs.writeFileSync(filePath, '{ corrupt json ...');

    expect(historyManager.getSessions()).toEqual([]);
  });
});
