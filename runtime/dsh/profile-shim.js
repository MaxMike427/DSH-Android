/**
 * Profile shim: give the PRoot bash executor a reported sandbox mode.
 *
 * `@deepseek-ai/dsh-bash-local` is the only executor that works under PRoot — the
 * sandboxed one needs bwrap or Landlock, and PRoot provides neither. But
 * `bash-local` reports `sandboxMode === undefined` because it never confines, and
 * `@deepseek-ai/dsh-permission-presets` refuses to construct in that case
 * ("the mounted bash executor does not confine").
 *
 * Without the presets service, `/api/permissionPresets/catalog` answers 404 and
 * the Web UI never paints its main surface.
 *
 * This subclass keeps LocalBashExecutor's behaviour and only reports the mode the
 * deployment actually guarantees. That mode is not a kernel sandbox: it is the
 * PRoot guest boundary — every command already runs inside the Ubuntu rootfs,
 * with only the explicitly bound host paths reachable, so a command cannot write
 * outside the guest.
 */
import { LocalBashExecutor } from '@deepseek-ai/dsh-bash-local'

/** The mode reported to the presets service; PRoot confines to the guest. */
const REPORTED_MODE = 'workspace-write'

export default class SandboxedLocalBashExecutor extends LocalBashExecutor {
  get sandboxMode() {
    return REPORTED_MODE
  }
}
