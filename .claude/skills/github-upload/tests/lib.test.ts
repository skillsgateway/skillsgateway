import assert from "node:assert/strict";
import { test } from "node:test";
import {
  contentTypeFor,
  markdownFor,
  matchesMagic,
  parseArgs,
  repoFromRemoteUrl,
  uploadFilesFromCommand,
  uploadUrl,
} from "../scripts/lib.ts";

test("contentTypeFor maps known extensions case-insensitively", () => {
  assert.equal(contentTypeFor("shot.png"), "image/png");
  assert.equal(contentTypeFor("SHOT.PNG"), "image/png");
  assert.equal(contentTypeFor("clip.webm"), "video/webm");
  assert.equal(contentTypeFor("photo.JPEG"), "image/jpeg");
});

test("contentTypeFor rejects non-media, extensionless, and svg files", () => {
  assert.equal(contentTypeFor("notes.pdf"), undefined);
  assert.equal(contentTypeFor("archive.tar.gz"), undefined);
  assert.equal(contentTypeFor("Makefile"), undefined);
  assert.equal(contentTypeFor("diagram.svg"), undefined);
});

test("matchesMagic accepts genuine headers", () => {
  const ascii = (s: string) => new Uint8Array([...s].map((c) => c.charCodeAt(0)));
  assert.ok(matchesMagic(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0]), "image/png"));
  assert.ok(matchesMagic(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]), "image/jpeg"));
  assert.ok(matchesMagic(ascii("GIF89a...."), "image/gif"));
  assert.ok(matchesMagic(ascii("RIFF\x10\x00\x00\x00WEBPVP8 "), "image/webp"));
  assert.ok(matchesMagic(ascii("\x00\x00\x00\x18ftypisom"), "video/mp4"));
  assert.ok(matchesMagic(new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0x01]), "video/webm"));
});

test("matchesMagic rejects disguised, truncated, and unknown-type files", () => {
  const envFile = new Uint8Array([...("SECRET_KEY=hunter2\n")].map((c) => c.charCodeAt(0)));
  assert.equal(matchesMagic(envFile, "image/png"), false);
  assert.equal(matchesMagic(new Uint8Array([0x89, 0x50]), "image/png"), false);
  assert.equal(matchesMagic(new Uint8Array(0), "image/gif"), false);
  assert.equal(matchesMagic(envFile, "image/svg+xml"), false);
});

test("uploadFilesFromCommand extracts files from attach.ts invocations only", () => {
  assert.equal(uploadFilesFromCommand("curl https://example.com"), undefined);
  assert.deepEqual(
    uploadFilesFromCommand("node plugins/github-tools/skills/upload/scripts/attach.ts a.png b.gif"),
    ["a.png", "b.gif"],
  );
  assert.deepEqual(
    uploadFilesFromCommand('node "/cache/skills/upload/scripts/attach.ts" --repo o/r "my shot.png"'),
    ["my shot.png"],
  );
  assert.deepEqual(
    uploadFilesFromCommand("node skills/upload/scripts/attach.ts --repo=o/r a.png && echo done"),
    ["a.png"],
  );
});

test("parseArgs collects files and --repo in both forms", () => {
  assert.deepEqual(parseArgs(["a.png", "b.gif"]), { files: ["a.png", "b.gif"] });
  assert.deepEqual(parseArgs(["--repo", "o/r", "a.png"]), { files: ["a.png"], repo: "o/r" });
  assert.deepEqual(parseArgs(["--repo=o/r", "a.png"]), { files: ["a.png"], repo: "o/r" });
});

test("parseArgs rejects bad input", () => {
  assert.throws(() => parseArgs(["--repo"]), /requires a value/);
  assert.throws(() => parseArgs(["--repo", "not-a-repo", "a.png"]), /owner\/name/);
  assert.throws(() => parseArgs(["--frobnicate", "a.png"]), /unknown option/);
});

test("repoFromRemoteUrl handles https, scp-like, and ssh github.com remotes", () => {
  assert.equal(repoFromRemoteUrl("https://github.com/owner/name.git"), "owner/name");
  assert.equal(repoFromRemoteUrl("https://github.com/owner/name"), "owner/name");
  assert.equal(repoFromRemoteUrl("git@github.com:owner/name.git"), "owner/name");
  assert.equal(repoFromRemoteUrl("ssh://git@github.com/owner/name.git"), "owner/name");
  assert.equal(repoFromRemoteUrl("git@github.com:owner/name.git\n"), "owner/name");
});

test("repoFromRemoteUrl refuses non-github.com hosts", () => {
  assert.equal(repoFromRemoteUrl("https://github.example.com/o/r.git"), undefined);
  assert.equal(repoFromRemoteUrl("git@gitlab.com:o/r.git"), undefined);
  assert.equal(repoFromRemoteUrl("https://evilgithub.com/o/r.git"), undefined);
});

test("uploadUrl encodes query parameters", () => {
  const url = uploadUrl("my shot.png", "image/png", 12345);
  assert.equal(
    url,
    "https://uploads.github.com/user-attachments/assets?name=my+shot.png&content_type=image%2Fpng&repository_id=12345",
  );
});

test("markdownFor derives readable alt text from the filename", () => {
  assert.equal(
    markdownFor("before-fix_v2.png", "https://github.com/user-attachments/assets/x"),
    "![before fix v2](https://github.com/user-attachments/assets/x)",
  );
});
