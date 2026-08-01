import * as vscode from 'vscode';
import * as path from 'path';
import * as child_process from 'child_process';

export function activate(context: vscode.ExtensionContext) {
  const provider = new OmniPilotViewProvider(context.extensionUri);

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('omnipilot.chatView', provider)
  );
}

class OmniPilotViewProvider implements vscode.WebviewViewProvider {
  private _view?: vscode.WebviewView;

  constructor(private readonly _extensionUri: vscode.Uri) {}

  public resolveWebviewView(
    webviewView: vscode.WebviewView,
    context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken,
  ) {
    this._view = webviewView;

    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [this._extensionUri]
    };

    webviewView.webview.html = this._getHtmlForWebview(webviewView.webview);
  }

  private _getHtmlForWebview(webview: vscode.Webview) {
    return `<!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <title>OmniPilot AI Chat</title>
      <style>
        body { font-family: sans-serif; padding: 10px; color: var(--vscode-editor-foreground); background: var(--vscode-editor-background); }
      </style>
    </head>
    <body>
      <h3>OmniPilot AI Chat</h3>
      <p>OmniPilot VS Code extension activated using omnipilot-server core.</p>
    </body>
    </html>`;
  }
}

export function deactivate() {}
