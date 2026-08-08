import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as child_process from 'child_process';
import { OmniPilotRpcClient } from './rpc-client';


let client: OmniPilotRpcClient | null = null;
let statusBarItem: vscode.StatusBarItem;
let chatPanel: vscode.WebviewView | undefined;
let settingsPanel: vscode.WebviewPanel | undefined;

export function activate(context: vscode.ExtensionContext) {
  // 1. Status bar
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBarItem.text = '$(hubot) OmniPilot';
  statusBarItem.tooltip = 'OmniPilot AI — click to open settings';
  statusBarItem.command = 'omnipilot.openSettings';
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  // 2. Start RPC server
  client = new OmniPilotRpcClient(context.extensionUri);
  client.start();

  // 3. Commands
  context.subscriptions.push(
    vscode.commands.registerCommand('omnipilot.newChat', () => {
      chatPanel?.webview.postMessage({ type: 'newChat' });
    }),
    vscode.commands.registerCommand('omnipilot.openSettings', () => {
      openSettingsPanel(context);
    })
  );

  // 4. Chat Webview provider
  const chatProvider = new OmniPilotChatProvider(context.extensionUri);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('omnipilot.chatView', chatProvider, {
      webviewOptions: { retainContextWhenHidden: true }
    })
  );

  // 5. Push providers to server on settings change
  pushProvidersToServer();
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration('omnipilot')) {
        pushProvidersToServer();
        chatPanel?.webview.postMessage({
          type: 'providersUpdated',
          providers: getProviders()
        });
        updateStatusBar();
      }
    })
  );

  // 6. Token notifications → webview
  client.onNotification('chat/token', (params: { sessionId: string; token: string }) => {
    chatPanel?.webview.postMessage({ type: 'token', token: params.token });
  });
  client.onNotification('chat/complete', (_params: { sessionId: string }) => {
    chatPanel?.webview.postMessage({ type: 'complete' });
  });
  client.onNotification('chat/error', (params: { sessionId: string; message: string }) => {
    chatPanel?.webview.postMessage({ type: 'error', message: params.message });
  });

  // 7. Server → client requests (agent tool execution + permission)
  client.onRequest('chat/toolCall', async (params: any) => {
    return await executeToolCall(params);
  });
  client.onRequest('chat/permissionRequest', async (params: any) => {
    return await requestPermission(params);
  });


  updateStatusBar();


  context.subscriptions.push({ dispose: () => { client?.stop(); } });
}

// ── Helpers ───────────────────────────────────────────────────────────────

function getProviders(): any[] {
  return vscode.workspace.getConfiguration('omnipilot').get<any[]>('providers') ?? [];
}

function pushProvidersToServer(): void {
  const providers = getProviders();
  client?.sendRequest('config/setProviders', { providers }).catch(() => {});
}

function updateStatusBar(): void {
  const config = vscode.workspace.getConfiguration('omnipilot');
  const providers = getProviders();
  const activeId = config.get<string>('activeProviderId') ?? '';
  const activeModel = config.get<string>('activeModel') ?? '';
  const provider = providers.find(p => p.id === activeId);
  if (provider && activeModel) {
    statusBarItem.text = `$(hubot) ${provider.name} · ${activeModel.split('/').pop()}`;
  } else if (provider) {
    statusBarItem.text = `$(hubot) ${provider.name}`;
  } else {
    statusBarItem.text = '$(hubot) OmniPilot';
  }
}

/**
 * Fetches available models directly from the provider API using Node.js https/http.
 * Does NOT go through the omnipilot-server child process.
 */
async function fetchModelsDirectly(baseUrl: string, apiKey: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const cleanBase = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const targetUrl = `${cleanBase}/models`;

    let urlObj: URL;
    try {
      urlObj = new URL(targetUrl);
    } catch {
      reject(new Error(`Invalid URL: ${targetUrl}`));
      return;
    }

    const isHttps = urlObj.protocol === 'https:';
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const transport = isHttps ? require('https') : require('http');

    const options = {
      hostname: urlObj.hostname,
      port: urlObj.port ? parseInt(urlObj.port, 10) : (isHttps ? 443 : 80),
      path: urlObj.pathname + urlObj.search,
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        ...(apiKey.trim() ? { 'Authorization': `Bearer ${apiKey.trim()}` } : {})
      }
    };

    const req = transport.request(options, (res: any) => {
      let data = '';
      res.on('data', (chunk: any) => { data += chunk.toString(); });
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (json && Array.isArray(json.data)) {
            resolve(json.data.map((m: any) => (m.id || m.name || '')).filter(Boolean));
          } else {
            resolve([]);
          }
        } catch {
          reject(new Error(`Failed to parse response: ${data.slice(0, 200)}`));
        }
      });
    });

    req.on('error', (err: Error) => reject(err));
    req.setTimeout(10000, () => {
      req.destroy();
      reject(new Error('Request timed out after 10s'));
    });
    req.end();
  });
}

function openSettingsPanel(context: vscode.ExtensionContext): void {
  if (settingsPanel) {
    settingsPanel.reveal(vscode.ViewColumn.One);
    return;
  }
  settingsPanel = vscode.window.createWebviewPanel(
    'omnipilot.settings',
    'OmniPilot Settings',
    vscode.ViewColumn.One,
    { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [context.extensionUri] }
  );
  const htmlPath = path.join(context.extensionUri.fsPath, 'resources', 'settings-webview.html');
  settingsPanel.webview.html = fs.readFileSync(htmlPath, 'utf8');

  settingsPanel.webview.onDidReceiveMessage(async (msg: any) => {
    const config = vscode.workspace.getConfiguration('omnipilot');
    switch (msg.type) {
      case 'settingsReady': {
        settingsPanel?.webview.postMessage({
          type: 'settingsInit',
          providers: getProviders(),
          activeProviderId: config.get<string>('activeProviderId') ?? '',
          activeModel: config.get<string>('activeModel') ?? '',
          enableInlineCompletions: config.get<boolean>('enableInlineCompletions') ?? true,
          autoApproveAgentMode: config.get<boolean>('autoApproveAgentMode') ?? false,
          mcpServerUrl: config.get<string>('mcpServerUrl') ?? ''
        });
        break;
      }
      case 'saveSettings': {
        const s = msg.settings;
        await config.update('providers', s.providers, vscode.ConfigurationTarget.Global);
        if (s.activeProviderId) {
          await config.update('activeProviderId', s.activeProviderId, vscode.ConfigurationTarget.Global);
        }
        if (s.activeModel) {
          await config.update('activeModel', s.activeModel, vscode.ConfigurationTarget.Global);
        }
        await config.update('enableInlineCompletions', s.enableInlineCompletions, vscode.ConfigurationTarget.Global);
        await config.update('autoApproveAgentMode', s.autoApproveAgentMode, vscode.ConfigurationTarget.Global);
        await config.update('mcpServerUrl', s.mcpServerUrl, vscode.ConfigurationTarget.Global);
        vscode.window.showInformationMessage('OmniPilot settings saved.');
        pushProvidersToServer();
        updateStatusBar();
        break;
      }
      case 'fetchModels': {
        try {
          const models = await fetchModelsDirectly(msg.baseUrl, msg.apiKey ?? '');
          settingsPanel?.webview.postMessage({ type: 'modelsResult', providerId: msg.providerId, models });
        } catch (e: any) {
          settingsPanel?.webview.postMessage({ type: 'modelsError', providerId: msg.providerId, error: e?.message ?? String(e) });
        }
        break;
      }
      case 'closeSettings': {
        settingsPanel?.dispose();
        break;
      }
    }
  });

  settingsPanel.onDidDispose(() => { settingsPanel = undefined; });
}

// ── Agent tool execution ──────────────────────────────────────────────────

function resolveWorkspacePath(p: string): string {
  if (path.isAbsolute(p)) {
    return p;
  }
  const folders = vscode.workspace.workspaceFolders;
  const base = folders && folders.length > 0 ? folders[0].uri.fsPath : process.cwd();
  return path.join(base, p);
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max) + `\n... (truncated, ${s.length} total chars)` : s;
}

async function executeToolCall(params: any): Promise<{ result: string }> {
  const name: string = params?.name ?? '';
  let args: any = {};
  try {
    args = typeof params?.arguments === 'string' ? JSON.parse(params.arguments) : (params?.arguments ?? {});
  } catch (e: any) {
    return { result: `Error: could not parse tool arguments: ${e?.message ?? String(e)}` };
  }

  try {
    switch (name) {
      case 'read_file': {
        const filePath = resolveWorkspacePath(String(args.filePath ?? args.path ?? ''));
        const content = fs.readFileSync(filePath, 'utf8');
        return { result: truncate(content, 20000) };
      }
      case 'write_file': {
        const filePath = resolveWorkspacePath(String(args.filePath ?? args.path ?? ''));

        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        fs.writeFileSync(filePath, String(args.content ?? ''), 'utf8');
        return { result: `File written: ${filePath}` };
      }
      case 'run_command': {
        const command = String(args.command ?? '');
        const output = await runShellCommand(command);
        return { result: truncate(output, 20000) };
      }
      default:
        return { result: `Error: unknown tool '${name}'` };
    }
  } catch (e: any) {
    return { result: `Error executing ${name}: ${e?.message ?? String(e)}` };
  }
}

function runShellCommand(command: string): Promise<string> {
  const folders = vscode.workspace.workspaceFolders;
  const cwd = folders && folders.length > 0 ? folders[0].uri.fsPath : process.cwd();
  return new Promise((resolve) => {
    child_process.exec(
      command,
      { timeout: 60000, maxBuffer: 1024 * 1024, windowsHide: true, cwd },

      (error, stdout, stderr) => {
        let out = '';
        if (stdout) out += stdout;
        if (stderr) out += (out ? '\n' : '') + stderr;
        if (error && !out) {
          out = `Command failed (exit ${error.code ?? '?'}): ${error.message}`;
        } else if (error) {
          out += `\n(exit code ${error.code ?? '?'})`;
        }
        resolve(out || '(no output)');
      }
    );
  });
}

async function requestPermission(params: any): Promise<{ decision: string }> {
  const tool: string = params?.tool ?? 'tool';
  let args: any = {};
  try {
    args = typeof params?.arguments === 'string' ? JSON.parse(params.arguments) : (params?.arguments ?? {});
  } catch { /* ignore */ }

  const autoApprove = vscode.workspace.getConfiguration('omnipilot').get<boolean>('autoApproveAgentMode') ?? false;
  if (autoApprove) {
    return { decision: 'ALLOW' };
  }

  let detail = '';
  if (tool === 'write_file') {
    detail = `Write to file:\n${args.filePath ?? args.path ?? ''}`;
  } else if (tool === 'read_file') {
    detail = `Read file:\n${args.filePath ?? args.path ?? ''}`;
  } else if (tool === 'run_command') {

    detail = `Run command:\n${args.command ?? ''}`;
  } else {
    detail = JSON.stringify(args);
  }

  const choice = await vscode.window.showWarningMessage(
    `OmniPilot Agent wants to: ${tool}`,
    { modal: true, detail },
    'Allow',
    'Deny'
  );
  return { decision: choice === 'Allow' ? 'ALLOW' : 'DENY' };
}

// ── Chat Webview Provider ─────────────────────────────────────────────────

class OmniPilotChatProvider implements vscode.WebviewViewProvider {

  constructor(private readonly extensionUri: vscode.Uri) {}

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ): void {
    chatPanel = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [this.extensionUri]
    };

    // Load HTML from resources/ (included in VSIX)
    const htmlPath = path.join(this.extensionUri.fsPath, 'resources', 'chat-webview.html');
    webviewView.webview.html = fs.readFileSync(htmlPath, 'utf8');

    webviewView.webview.onDidReceiveMessage(async (msg: any) => {
      switch (msg.type) {
        case 'ready': {
          const providers = getProviders();
          const config = vscode.workspace.getConfiguration('omnipilot');
          webviewView.webview.postMessage({
            type: 'init',
            providers,
            activeProviderId: config.get<string>('activeProviderId') ?? providers[0]?.id ?? '',
            activeModel: config.get<string>('activeModel') ?? '',
          });
          break;
        }
        case 'sendMessage': {
          try {
            const result = await client?.sendRequest<{ status: string; responseText: string }>('chat/send', {
              sessionId: msg.sessionId,
              messages: msg.messages,
              providerId: msg.providerId,
              baseUrl: msg.baseUrl,
              apiKey: msg.apiKey,
              model: msg.model,
              mode: msg.mode === 'Agent (Auto)' ? 'AGENT' : 'CHAT',
              osInfo: process.platform
            });
            // Save history after every successful AI turn
            if (result && result.responseText) {
              const allMessages = [
                ...(msg.messages || []),
                { role: 'assistant', content: result.responseText }
              ];
              const firstUserMsg = msg.messages?.find((m: any) => m.role === 'user');
              const title = (firstUserMsg?.content || 'Chat').slice(0, 60);
              client?.sendRequest('history/save', {
                session: {
                  id: msg.sessionId,
                  title,
                  timestamp: Date.now(),
                  messages: allMessages
                }
              }).catch(() => { /* history save failures are non-fatal */ });
            }
          } catch (e: any) {
            webviewView.webview.postMessage({ type: 'error', message: e?.message ?? String(e) });
          }
          const config = vscode.workspace.getConfiguration('omnipilot');
          await config.update('activeProviderId', msg.providerId, vscode.ConfigurationTarget.Global);
          await config.update('activeModel', msg.model, vscode.ConfigurationTarget.Global);
          updateStatusBar();
          break;
        }
        case 'cancelStream': {
          client?.sendRequest('chat/cancel').catch(() => {});
          break;
        }
        case 'newChat': { break; }
        case 'fetchModels': {
          const providers = getProviders();
          const provider = providers.find(p => p.id === msg.providerId);
          if (!provider) break;
          try {
            // fetchModels returns string[] directly
            const models = await client?.sendRequest<string[]>('models/fetch', {
              baseUrl: provider.baseUrl,
              apiKey: provider.apiKey
            }) ?? [];
            // Also check provider.models array as fallback
            const finalModels = models.length > 0 ? models : (provider.models ?? []);
            webviewView.webview.postMessage({ type: 'models', providerId: msg.providerId, models: finalModels });
          } catch {
            const fallback = provider.models ?? [];
            webviewView.webview.postMessage({ type: 'models', providerId: msg.providerId, models: fallback });
          }
          break;
        }
        case 'loadHistory': {
          try {
            const sessions = await client?.sendRequest<any[]>('history/list') ?? [];
            webviewView.webview.postMessage({ type: 'historyData', sessions });
          } catch {
            webviewView.webview.postMessage({ type: 'historyData', sessions: [] });
          }
          break;
        }
        case 'loadSession': {
          try {
            const session = await client?.sendRequest<any>('history/load', { id: msg.id });
            webviewView.webview.postMessage({ type: 'sessionLoaded', messages: session?.messages ?? [] });
          } catch {}
          break;
        }
        case 'deleteSession': {
          client?.sendRequest('history/delete', { id: msg.id }).catch(() => {});
          break;
        }
        case 'openSettings': {
          vscode.commands.executeCommand('omnipilot.openSettings');
          break;
        }
      }
    });
  }
}

export function deactivate() {
  client?.stop();
}
