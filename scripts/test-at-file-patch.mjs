/**
 * Runs the `dsh-at-file` patch exactly as `scripts/build-rootfs.sh` will run it
 * inside the PRoot guest.
 *
 * The heredoc is extracted from the build script rather than copied, so the test
 * cannot drift from the thing it is meant to check — and a quoting or escaping
 * mistake is caught here instead of after a two-hour ARM64 image build.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const buildScript = fs.readFileSync('scripts/build-rootfs.sh', 'utf8')

// The heredoc is embedded in a single-quoted `bash -lc '...'` string, so its
// delimiter is written with the '"'"' escape dance. Match either form.
const start = buildScript.indexOf('node - "$at_file" <<')
if (start < 0) {
  console.error('could not find the node heredoc in build-rootfs.sh')
  process.exit(2)
}
const lineEnd = buildScript.indexOf('\n', start)
const bodyStart = lineEnd + 1
const terminator = buildScript.indexOf('\nNODE\n', bodyStart)
if (terminator < 0) {
  console.error('could not find the terminal NODE line')
  process.exit(2)
}
const script = buildScript.slice(bodyStart, terminator + 1)

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'atfile-'))
const file = path.join(dir, 'index.js')
fs.writeFileSync(
  file,
  [
    'import { settingsNamespace } from "@deepseek-ai/dsh-settings";',
    'var AT_FILE_NAMESPACE = settingsNamespace("at-file");',
    'ctx.settings.register(AT_FILE_NAMESPACE, schema, { applies: "live" });',
    '',
  ].join('\n'),
  'utf8',
)

const scriptPath = path.join(dir, 'patch.cjs')
fs.writeFileSync(scriptPath, script, 'utf8')

// Same invocation the build uses: node - <file>
execFileSync(process.execPath, [scriptPath, file], { stdio: 'inherit' })
// The heredoc's own `process.argv[2]` indexing assumed `node -`, so also run it
// through stdin to prove the real calling convention works.
fs.writeFileSync(
  file,
  [
    'import { settingsNamespace } from "@deepseek-ai/dsh-settings";',
    'var AT_FILE_NAMESPACE = settingsNamespace("at-file");',
    'ctx.settings.register(AT_FILE_NAMESPACE, schema, { applies: "live" });',
    '',
  ].join('\n'),
  'utf8',
)
execFileSync(process.execPath, ['-', file], { input: script, stdio: ['pipe', 'inherit', 'inherit'] })

const after = fs.readFileSync(file, 'utf8')
const checks = {
  'import removed': !after.includes('@deepseek-ai/dsh-settings'),
  'helper call inlined': after.includes('var AT_FILE_NAMESPACE = "at-file";'),
  'no dangling helper': !after.includes('settingsNamespace('),
  'rest of file intact': after.includes('ctx.settings.register(AT_FILE_NAMESPACE'),
}
for (const [name, ok] of Object.entries(checks)) {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`)
}
const failed = Object.values(checks).some(v => !v)
console.log(failed ? 'RESULT: FAIL' : 'RESULT: PASS')
process.exit(failed ? 1 : 0)
