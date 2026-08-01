import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { OmniPilotRpcClient } from './rpc-client';

let client: OmniPilotRpcClient | null = null;
let statusBarItem: vscode.StatusBarItem;
let chatPanel: vscode.WebviewView | undefined;
let settingsPanel: vscode.WebviewPanel | undefined;

export function activate(context: vscode.ExtensionContext) {
  // 1. Status bar
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBarItem.text = '$(robot) OmniPilot';
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
    statusBarItem.text = `$(robot) ${provider.name} · ${activeModel.split('/').pop()}`;
  } else if (provider) {
    statusBarItem.text = `$(robot) ${provider.name}`;
  } else {
    statusBarItem.text = '$(robot) OmniPilot';
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
            await client?.sendRequest('chat/send', {
              sessionId: msg.sessionId,
              messages: msg.messages,
              providerId: msg.providerId,
              baseUrl: msg.baseUrl,
              apiKey: msg.apiKey,
              model: msg.model,
              mode: msg.mode === 'Agent (Auto)' ? 'AGENT' : 'CHAT',
              osInfo: process.platform
            });
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
