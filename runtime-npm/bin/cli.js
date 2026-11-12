#!/usr/bin/env node
'use strict';
// Launcher: delegates to the bundled Python executor runtime (stdlib-only, Python 3.11+).
// install-service / uninstall-service are handled in Node: they wrap this launcher into a
// launchd/systemd/schtasks daemon instead of starting the runtime.
const { spawn } = require('child_process');
const path = require('path');

const packageRoot = path.join(__dirname, '..');
const args = process.argv.slice(2);
const known = new Set(['connect', 'install-service', 'uninstall-service', '--once-smoke', '--check-config', '--help']);
if (args.length === 0 || !known.has(args[0])) {
  console.error('Usage: ymai-codeflow connect --ws-url <url> --token <token> --executor-id <id> [--provider claude] [--memory-mode platform] [--max-tasks N]');
  console.error('       ymai-codeflow install-service [--dry-run] [--os darwin|linux|windows] connect --ws-url <url> --token <token> --executor-id <id> [connect options…]');
  console.error('       ymai-codeflow uninstall-service --executor-id <id> [--os darwin|linux|windows]');
  console.error('       ymai-codeflow --check-config | --once-smoke | --help');
  console.error('Requires Python 3.11+ on the target machine (not needed for install-service).');
  process.exit(args.length === 0 ? 2 : 1);
}

// Service management never touches Python: it only writes platform daemon definitions.
if (args[0] === 'install-service' || args[0] === 'uninstall-service') {
  const service = require('./service');
  if (args[0] === 'install-service') service.doInstall(args.slice(1));
  else service.doUninstall(args.slice(1));
  process.exit(process.exitCode ?? 0);
}

const pythonCandidates = process.env.AINATIVE_PYTHON
  ? [process.env.AINATIVE_PYTHON]
  : ['python3', 'python3.11', 'python'];

let child;
(function tryNext(i) {
  if (i >= pythonCandidates.length) {
    console.error('Python 3.11+ not found. Install Python 3.11+ or set AINATIVE_PYTHON=/path/to/python3.');
    process.exit(1);
  }
  const py = pythonCandidates[i];
  child = spawn(py, ['-m', 'runtime.daemon', ...args], {
    cwd: packageRoot,
    stdio: 'inherit',
    env: process.env,
  });
  child.on('error', (err) => {
    if (err.code === 'ENOENT') { tryNext(i + 1); return; }
    console.error(err.message);
    process.exit(1);
  });
  child.on('exit', (code2) => process.exit(code2 ?? 0));
})(0);
