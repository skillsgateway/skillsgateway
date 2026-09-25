/**
 * Path arithmetic for the snapshot explorer. The tree itself is the gateway's — it is read one
 * directory at a time from `GET /api/v1/snapshots/{id}/tree` — so all that is left to the browser
 * is knowing which directories have to be open for an addressed path to be visible.
 *
 * @Requirements GW_INGEST_0032
 */

/** Every directory path on the way to `path` — what has to be open for it to be visible. */
export function ancestorDirectories(path: string): string[] {
  const segments = path.split("/").filter((segment) => segment.length > 0);
  const ancestors: string[] = [];
  let prefix = "";
  for (const segment of segments.slice(0, -1)) {
    prefix = prefix === "" ? segment : `${prefix}/${segment}`;
    ancestors.push(prefix);
  }
  return ancestors;
}
