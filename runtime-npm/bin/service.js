'use strict';
/**
 * install-service / uninstall-service: turn one runner into a platform-managed daemon.
 *
 *   - macOS:  launchd LaunchAgent  (KeepAlive + RunAtLoad, logs under ~/Library/Logs)
 *   - Linux:  systemd user unit    (Restart=always, journald logs; linger best-effort)
 *   - Windows: scheduled task at logon running a self-restarting wrapper script
 *
 * The daemon runs `node <cli.js> connect ...` directly (not via npx) so the service
 * does not depend on the npx cache. `--dry-run` prints every file and command instead
 * of touching the system; `--os darwin|linux|windows` overrides detection for tests.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

/** Parse `install-service [--dry-run] [--os X] [--executor-id Y] [--log-dir Z] connect <connect args…>`. */
function parseInstallArgs(argv) {
  const options = { dryRun: false, os: null, logDir: null, executorId: null, connectArgs: [] };
  let index = 0;
  while (index < argv.length) {
    const arg = argv[index];
    if (arg === 'connect') { options.connectArgs = argv.slice(index + 1); break; }
    if (arg === '--dry-run') { options.dryRun = true; index += 1; continue; }
    if (arg === '--executor-id') { options.executorId = argv[index + 1]; index += 2; continue; }
    if (arg === '--os') { options.os = argv[index + 1]; index += 2; continue; }
    if (arg === '--log-dir') { options.logDir = argv[index + 1]; index += 2; continue; }
    console.error(`install-service: unexpected argument "${arg}" before "connect"`);
    process.exit(1);
  }
  return options;
}

/** Extract `--flag value` pairs from the connect args; bare flags become true. */
function flagValue(connectArgs, flag) {
  const index = connectArgs.indexOf(flag);
  if (index < 0) return null;
  return connectArgs[index + 1] ?? null;
}

function requireConnectArgs(connectArgs) {
  const missing = ['--ws-url', '--token', '--executor-id'].filter(flag => !connectArgs.includes(flag));
  if (missing.length) {
    console.error(`install-service: missing required connect arguments: ${missing.join(', ')}`);
    console.error('Usage: ymai-codeflow install-service connect --ws-url <url> --token <token> --executor-id <id> [connect options…]');
    process.exit(1);
  }
}

function xmlEscape(value) {
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

/** systemd unit quoting: wrap in double quotes, escape \ " $ so the value survives unit parsing. */
function unitQuote(value) {
  return `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$$')}"`;
}

/** cmd.exe quoting: wrap in double quotes; inner quotes doubled (caret-safe for schtasks /TR). */
function cmdQuote(value) {
  return `"${String(value).replace(/"/g, '""')}"`;
}

function homeRelative(absPath) {
  const home = os.homedir();
  return absPath.startsWith(home) ? absPath.slice(home.length) : absPath;
}

/** launchd LaunchAgent definition (macOS). */
function darwinPlan(executorId, connectArgs, cliPath, logDir) {
  const label = `com.ainative.executor.${executorId}`;
  const agentDir = path.join(os.homedir(), 'Library', 'LaunchAgents');
  const filePath = path.join(agentDir, `${label}.plist`);
  const stdoutLog = path.join(logDir, `${label}.log`);
  const stderrLog = path.join(logDir, `${label}.err.log`);
  const programArguments = [process.execPath, cliPath, 'connect', ...connectArgs];
  const plist = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${xmlEscape(label)}</string>
  <key>ProgramArguments</key>
  <array>
${programArguments.map(arg => `    <string>${xmlEscape(arg)}</string>`).join('\n')}
  </array>
  <key>KeepAlive</key><true/>
  <key>RunAtLoad</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>${xmlEscape(stdoutLog)}</string>
  <key>StandardErrorPath</key><string>${xmlEscape(stderrLog)}</string>
</dict>
</plist>
`;
  const uid = typeof process.getuid === 'function' ? process.getuid() : null;
  return {
    platform: 'darwin',
    label,
    filePath,
    content: plist,
    logs: [stdoutLog, stderrLog],
    install: uid != null
      ? [`launchctl bootstrap gui/${uid} ${filePath}`, `launchctl enable gui/${uid}/${label} 2>/dev/null || true`]
      : [`launchctl load ${filePath}`],
    // bootout takes either the domain-target form or the label; cover both launchd generations.
    uninstall: uid != null
      ? [`launchctl bootout gui/${uid}/${label} 2>/dev/null || launchctl remove ${label} 2>/dev/null || true`]
      : [`launchctl unload ${filePath} 2>/dev/null || true`],
  };
}

/** systemd user unit (Linux). */
function linuxPlan(executorId, connectArgs, cliPath) {
  const unitName = `ymai-codeflow-${executorId}.service`;
  const unitDir = path.join(os.homedir(), '.config', 'systemd', 'user');
  const filePath = path.join(unitDir, unitName);
  const programArguments = [process.execPath, cliPath, 'connect', ...connectArgs];
  const unit = `[Unit]
Description=AINative CodeFlow executor ${executorId}
After=network-online.target

[Service]
ExecStart=${programArguments.map(unitQuote).join(' ')}
Restart=always
RestartSec=10
Environment=AINATIVE_SERVICE=1

[Install]
WantedBy=default.target
`;
  return {
    platform: 'linux',
    label: unitName.replace(/\.service$/, ''),
    filePath,
    content: unit,
    logs: ['journalctl --user -u ymai-codeflow-' + executorId + ' -f'],
    install: [
      `systemctl --user daemon-reload`,
      `systemctl --user enable --now ${unitName}`,
      `loginctl enable-linger "$USER" 2>/dev/null || echo 'hint: run "sudo loginctl enable-linger $USER" so the unit survives logout'`,
    ],
    uninstall: [
      `systemctl --user disable --now ${unitName}`,
    ],
  };
}

/** Scheduled task + self-restarting wrapper (Windows). */
function windowsPlan(executorId, connectArgs, cliPath, logDir) {
  const taskName = `AINative CodeFlow Executor ${executorId}`;
  const appDir = path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'AINative');
  const wrapperPath = path.join(appDir, `ymai-codeflow-${executorId}.cmd`);
  const logFile = path.join(logDir, `ymai-codeflow-${executorId}.log`);
  const nodeExe = process.execPath;
  // A restart loop gives KeepAlive semantics without a service host binary.
  const wrapper = `@echo off
title AINative CodeFlow Executor ${executorId}
:loop
${cmdQuote(nodeExe)} ${cmdQuote(cliPath)} connect ${connectArgs.map(cmdQuote).join(' ')} >> ${cmdQuote(logFile)} 2>&1
echo [%date% %time%] runner exited, restarting in 10s >> ${cmdQuote(logFile)}
timeout /t 10 /nobreak >nul
goto loop
`;
  const create = [
    `schtasks /Create /TN ${cmdQuote(taskName)} /SC ONLOGON /TR ${cmdQuote(`cmd /c "${wrapperPath}"`)} /F`,
  ];
  return {
    platform: 'windows',
    label: taskName,
    filePath: wrapperPath,
    content: wrapper,
    logs: [logFile],
    install: create,
    uninstall: [`schtasks /Delete /TN ${cmdQuote(taskName)} /F 2>nul`],
  };
}

function planFor(platform, executorId, connectArgs, cliPath, logDir) {
  if (platform === 'darwin') return darwinPlan(executorId, connectArgs, cliPath, logDir);
  if (platform === 'linux') return linuxPlan(executorId, connectArgs, cliPath);
  if (platform === 'windows') return windowsPlan(executorId, connectArgs, cliPath, logDir);
  console.error(`install-service: unsupported platform "${platform}"`);
  process.exit(1);
}

function run(command, dryRun) {
  if (dryRun) { console.log(`  (dry-run) $ ${command}`); return { status: 0 }; }
  return spawnSync(command, { shell: true, stdio: 'inherit' });
}

function doInstall(argv) {
  const options = parseInstallArgs(argv);
  requireConnectArgs(options.connectArgs);
  const executorId = options.executorId ?? flagValue(options.connectArgs, '--executor-id');
  const platform = options.os || process.platform;
  const cliPath = path.resolve(__dirname, 'cli.js');
  const logDir = options.logDir || defaultLogDir(platform);
  const plan = planFor(platform, String(executorId), options.connectArgs, cliPath, logDir);

  console.log(`Installing executor ${executorId} as a ${plan.platform} service (${plan.label})`);
  if (options.dryRun) console.log('— dry run: nothing is written or started —');
  console.log(`service file: ${plan.filePath}`);

  if (options.dryRun) {
    console.log('---- file content ----');
    console.log(plan.content);
    console.log('---- install commands ----');
    plan.install.forEach(cmd => run(cmd, true));
    return;
  }

  fs.mkdirSync(path.dirname(plan.filePath), { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  if (fs.existsSync(plan.filePath)) {
    plan.uninstall.forEach(cmd => run(cmd, options.dryRun));
    console.log('existing installation removed first');
  }
  fs.writeFileSync(plan.filePath, plan.content, { mode: 0o600 });
  console.log(`written ${plan.filePath} (chmod 600 — the file embeds the runner token)`);
  for (const cmd of plan.install) {
    const result = run(cmd, options.dryRun);
    if (result.status !== 0 && !cmd.includes('||')) {
      console.error(`command failed (exit ${result.status}): ${cmd}`);
      process.exitCode = 1;
    }
  }
  console.log('done. logs:');
  plan.logs.forEach(log => console.log(`  ${log}`));
}

function doUninstall(argv) {
  const options = parseInstallArgs(argv);
  const executorId = options.executorId ?? flagValue(argv, '--executor-id');
  if (!executorId) {
    console.error('uninstall-service: --executor-id is required');
    process.exit(1);
  }
  const platform = options.os || process.platform;
  const cliPath = path.resolve(__dirname, 'cli.js');
  const logDir = options.logDir || defaultLogDir(platform);
  const plan = planFor(platform, String(executorId), [], cliPath, logDir);
  console.log(`Uninstalling ${plan.platform} service ${plan.label}`);
  if (options.dryRun) {
    plan.uninstall.forEach(cmd => run(cmd, true));
    console.log(`(dry-run) would remove ${plan.filePath}`);
    return;
  }
  plan.uninstall.forEach(cmd => run(cmd, options.dryRun));
  if (fs.existsSync(plan.filePath)) {
    fs.rmSync(plan.filePath);
    console.log(`removed ${plan.filePath}`);
  }
  console.log('done.');
}

function defaultLogDir(platform) {
  if (platform === 'darwin') return path.join(os.homedir(), 'Library', 'Logs');
  if (platform === 'windows') return path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'AINative', 'logs');
  return path.join(os.homedir(), '.local', 'state', 'ymai-codeflow', 'logs');
}

module.exports = { doInstall, doUninstall, parseInstallArgs, planFor, defaultLogDir };

if (require.main === module) {
  const [sub, ...rest] = process.argv.slice(2);
  if (sub === 'install-service') doInstall(rest);
  else if (sub === 'uninstall-service') doUninstall(rest);
  else { console.error('usage: service.js install-service|uninstall-service …'); process.exit(2); }
}
