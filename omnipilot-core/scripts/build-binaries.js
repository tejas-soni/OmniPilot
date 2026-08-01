import * as esbuild from 'esbuild';
import * as fs from 'fs';
import * as path from 'path';

async function build() {
  const distDir = path.resolve('dist');
  if (!fs.existsSync(distDir)) {
    fs.mkdirSync(distDir, { recursive: true });
  }

  await esbuild.build({
    entryPoints: ['src/index.ts'],
    bundle: true,
    platform: 'node',
    target: 'node18',
    outfile: 'dist/omnipilot-server.js',
    minify: false,
    sourcemap: true
  });

  console.log('Successfully bundled omnipilot-server.js');
}

build().catch((err) => {
  console.error(err);
  process.exit(1);
});
