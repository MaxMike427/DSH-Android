/**
 * Extracts PET_ONLY_SCRIPT from PetOnlyScript.kt so the debug driver can inject
 * the exact same text into the live overlay page.
 */
import fs from 'node:fs'

const src = fs.readFileSync('app/src/main/java/com/dsh/android/pet/PetOnlyScript.kt', 'utf8')
const start = src.indexOf('"""')
const end = src.lastIndexOf('"""')
if (start < 0 || end <= start) {
  console.error('no script literal found')
  process.exit(1)
}
let body = src.slice(start + 3, end)
// Kotlin's trimIndent on a raw string starting right after """ leaves a leading
// newline; strip the surrounding whitespace the same way.
body = body.replace(/^\r?\n/, '').replace(/\s+$/, '')
process.stdout.write(body)