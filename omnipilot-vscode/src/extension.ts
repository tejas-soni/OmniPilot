import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { OmniPilotRpcClient } from './rpc-client';

let client: OmniPilotRpcClient | null = null;
let statusBarItem: vscode.StatusBarItem;
let chatPanel: vscode.WebviewView | undefined;

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
      vscode.commands.executeCommand('workbench.action.openSettings', 'omnipilot');
    })
  );

  // 4. Webview provider
  const provider = new OmniPilotViewProvider(context.extensionUri);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('omnipilot.chatView', provider, {
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
  client.onNotification('chat/complete', (params: { sessionId: string }) => {
    chatPanel?.webview.postMessage({ type: 'complete' });
  });
  client.onNotification('chat/error', (params: { sessionId: string; message: string }) => {
    chatPanel?.webview.postMessage({ type: 'error', message: params.message });
  });

  updateStatusBar();

  context.subscriptions.push({
    dispose: () => { client?.stop(); }
  });
}

function getProviders(): any[] {
  const config = vscode.workspace.getConfiguration('omnipilot');
  return config.get<any[]>('providers') ?? [];
}

function pushProvidersToServer(): void {
  const providers = getProviders();
  client?.sendRequest('config/setProviders', { providers }).catch(() => {});
}

function updateStatusBar(): void {
  const providers = getProviders();
  const config = vscode.workspace.getConfiguration('omnipilot');
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

class OmniPilotViewProvider implements vscode.WebviewViewProvider {
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

    // Load HTML
    const htmlPath = path.join(this.extensionUri.fsPath, 'src', 'chat-webview.html');
    webviewView.webview.html = fs.readFileSync(htmlPath, 'utf8');

    // Handle messages from webview
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
          // Save active model/provider to settings
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
        case 'newChat': {
          // Nothing to do server-side for new chat
          break;
        }
        case 'fetchModels': {
          const providers = getProviders();
          const provider = providers.find(p => p.id === msg.providerId);
          if (!provider) break;
          try {
            const result = await client?.sendRequest<{ models: string[] }>('models/fetch', {
              baseUrl: provider.baseUrl,
              apiKey: provider.apiKey
            });
            const models: string[] = result?.models ?? (provider.models ? provider.models.split(',').map((m: string) => m.trim()) : []);
            webviewView.webview.postMessage({ type: 'models', providerId: msg.providerId, models });
          } catch {
            // Fallback to configured models string
            const fallback = provider.models ? provider.models.split(',').map((m: string) => m.trim()) : [];
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
          vscode.commands.executeCommand('workbench.action.openSettings', 'omnipilot');
          break;
        }
      }
    });
  }
}

export function deactivate() {
  client?.stop();
}
