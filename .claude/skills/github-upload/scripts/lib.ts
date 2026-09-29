// Pure helpers for attach.ts — no I/O, unit-tested in ../tests/.

export const UPLOAD_ENDPOINT = "https://uploads.github.com/user-attachments/assets";

// The endpoint accepts images and video only; anything else is rejected.
// SVG is deliberately absent even though the endpoint takes it: it has no
// magic number to verify against and is the only scriptable format.
export const CONTENT_TYPES: Record<string, string> = {
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".mp4": "video/mp4",
  ".webm": "video/webm",
};

export function contentTypeFor(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0) return undefined;
  return CONTENT_TYPES[fileName.slice(dot).toLowerCase()];
}

export interface CliArgs {
  files: string[];
  repo?: string;
}

export function parseArgs(argv: string[]): CliArgs {
  const args: CliArgs = { files: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--repo") {
      const value = argv[++i];
      if (!value) throw new Error("--repo requires a value (owner/name)");
      args.repo = value;
    } else if (arg.startsWith("--repo=")) {
      args.repo = arg.slice("--repo=".length);
    } else if (arg.startsWith("-")) {
      throw new Error(`unknown option: ${arg}`);
    } else {
      args.files.push(arg);
    }
  }
  if (args.repo && !/^[^/\s]+\/[^/\s]+$/.test(args.repo)) {
    throw new Error(`--repo must be owner/name, got: ${args.repo}`);
  }
  return args;
}

// Extract owner/name from a github.com remote URL (https, ssh, or scp-like).
// Returns undefined for non-github.com hosts — the upload endpoint is
// github.com-specific, so GHES remotes must not silently "work".
export function repoFromRemoteUrl(url: string): string | undefined {
  const trimmed = url.trim();
  const match =
    /^https:\/\/github\.com\/([^/\s]+)\/([^/\s]+?)(?:\.git)?\/?$/.exec(trimmed) ??
    /^(?:ssh:\/\/)?git@github\.com[:/]([^/\s]+)\/([^/\s]+?)(?:\.git)?$/.exec(trimmed);
  if (!match) return undefined;
  return `${match[1]}/${match[2]}`;
}

function bytesAt(bytes: Uint8Array, offset: number, expected: number[] | string): boolean {
  const want = typeof expected === "string" ? [...expected].map((c) => c.charCodeAt(0)) : expected;
  if (bytes.length < offset + want.length) return false;
  return want.every((b, i) => bytes[offset + i] === b);
}

// Magic-number check: does the file content actually match the claimed type?
// Guards against disguised files (`mv secrets.env secrets.png`) — an extension
// check alone would happily upload them.
export function matchesMagic(bytes: Uint8Array, mime: string): boolean {
  switch (mime) {
    case "image/png":
      return bytesAt(bytes, 0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    case "image/jpeg":
      return bytesAt(bytes, 0, [0xff, 0xd8, 0xff]);
    case "image/gif":
      return bytesAt(bytes, 0, "GIF87a") || bytesAt(bytes, 0, "GIF89a");
    case "image/webp":
      return bytesAt(bytes, 0, "RIFF") && bytesAt(bytes, 8, "WEBP");
    case "video/mp4":
      return bytesAt(bytes, 4, "ftyp");
    case "video/webm":
      return bytesAt(bytes, 0, [0x1a, 0x45, 0xdf, 0xa3]);
    default:
      return false;
  }
}

// Extract the file arguments from a shell command that invokes this skill's
// attach.ts, or undefined if the command doesn't invoke it. Used by the
// plugin's PreToolUse hook to verify files before the command runs.
export function uploadFilesFromCommand(command: string): string[] | undefined {
  const tokens = [...command.matchAll(/"([^"]*)"|'([^']*)'|(\S+)/g)].map(
    (m) => m[1] ?? m[2] ?? m[3],
  );
  const scriptIndex = tokens.findIndex((t) => /skills\/upload\/scripts\/attach\.ts$/.test(t));
  if (scriptIndex < 0) return undefined;
  const files: string[] = [];
  for (let i = scriptIndex + 1; i < tokens.length; i++) {
    const token = tokens[i];
    if (token === "--repo") {
      i++;
    } else if (token.startsWith("--repo=")) {
      continue;
    } else if (/^(&&|\|\|?|;|>>?|2>)/.test(token)) {
      break; // end of this command in a compound line
    } else {
      files.push(token);
    }
  }
  return files;
}

export function uploadUrl(fileName: string, mime: string, repositoryId: number): string {
  const query = new URLSearchParams({
    name: fileName,
    content_type: mime,
    repository_id: String(repositoryId),
  });
  return `${UPLOAD_ENDPOINT}?${query}`;
}

export function markdownFor(fileName: string, url: string): string {
  const stem = fileName.replace(/\.[^.]+$/, "");
  const alt = stem.replace(/[-_]+/g, " ").trim() || fileName;
  return `![${alt}](${url})`;
}
