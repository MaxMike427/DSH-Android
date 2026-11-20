// Removes one entry from dsh.profile.bundles in a profile package.json, so the
// device can be brought to the same plugin set the image will ship.
//
// Usage: node scripts/prune-bundle.mjs <package.json> <bundle-name>
import fs from 'node:fs'

const [, , file, name] = process.argv
if (!file || !name) {
  console.error('usage: node scripts/prune-bundle.mjs <package.json> <bundle-name>')
  process.exit(2)
}

const manifest = JSON.parse(fs.readFileSync(file, 'utf8'))
const bundles = manifest?.dsh?.profile?.bundles
if (!Array.isArray(bundles)) {
  console.error('no dsh.profile.bundles array in ' + file)
  process.exit(2)
}

const before = bundles.length
manifest.dsh.profile.bundles = bundles.filter((entry) => entry !== name)
if (manifest.dependencies) delete manifest.dependencies[name]
fs.writeFileSync(file, JSON.stringify(manifest, null, 2) + '\n', 'utf8')
console.log(`bundles ${before} -> ${manifest.dsh.profile.bundles.length} (removed ${name})`)
