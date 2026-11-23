import type { VolumeView } from "../api/types.ts";

/** A new, empty row in the volume list. */
export function blankVolume(): VolumeView {
  return { hostPath: "", containerPath: "", readOnly: false };
}

/** What is wrong with a row before it is sent; undefined when it can be sent (an empty row is dropped). */
export function volumeProblem(v: VolumeView): string | undefined {
  const host = v.hostPath.trim();
  const container = v.containerPath.trim();
  if (!host && !container) {
    return undefined;
  }
  if (!host || !container) {
    return "A folder needs both paths: where it is on this machine and where it appears in the container.";
  }
  if (!host.startsWith("/") && !host.startsWith("~")) {
    return `${host}: the folder on this machine must be an absolute path or start with ~/.`;
  }
  if (!container.startsWith("/")) {
    return `${container}: the path in the container must be absolute, e.g. /deployments/config.`;
  }
  return undefined;
}

/** The rows to send: trimmed, empty ones left out. */
export function cleanVolumes(volumes: VolumeView[]): VolumeView[] {
  return volumes
    .map((v) => ({ hostPath: v.hostPath.trim(), containerPath: v.containerPath.trim(), readOnly: v.readOnly }))
    .filter((v) => v.hostPath || v.containerPath);
}
