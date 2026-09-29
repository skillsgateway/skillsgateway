#!/usr/bin/env node
// Upload image/video files as GitHub attachments and print markdown, one line
// per file. Requires Node 22.18+ (built-in fetch + type stripping). No
// dependencies; `gh` and `git` are optional conveniences, not requirements.
//
// Usage: node attach.ts [--repo owner/name] <file> [file ...]
//
// Token:  `gh auth token` if gh is installed, else GITHUB_TOKEN or GH_TOKEN.
// Repo:   --repo flag, else GITHUB_REPOSITORY env, else the git origin remote.
//         Needs push access; github.com only (not GHES).

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import process from "node:process";
import {
  contentTypeFor,
  markdownFor,
  matchesMagic,
  parseArgs,
  repoFromRemoteUrl,
  uploadUrl,
} from "./lib.ts";

function fail(message: string): never {
  console.error(`error: ${message}`);
  process.exit(1);
}

function tryExec(command: string, args: string[]): string | undefined {
  try {
    return execFileSync(command, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return undefined; // command missing or failed — caller falls back
  }
}

function resolveToken(): string {
  const fromGh = tryExec("gh", ["auth", "token"]);
  if (fromGh) return fromGh;
  const fromEnv = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
  if (fromEnv) return fromEnv;
  fail(
    "no GitHub token found. Either log in with the GitHub CLI (`gh auth login`) " +
      "or set GITHUB_TOKEN (or GH_TOKEN) to a token with push access to the repository.",
  );
}

function resolveRepo(flag: string | undefined): string {
  if (flag) return flag;
  if (process.env.GITHUB_REPOSITORY) return process.env.GITHUB_REPOSITORY;
  const remote = tryExec("git", ["remote", "get-url", "origin"]);
  if (remote) {
    const repo = repoFromRemoteUrl(remote);
    if (repo) return repo;
    fail(
      `origin remote (${remote}) is not a github.com repository — ` +
        "this upload endpoint only exists on github.com. Pass --repo owner/name to override.",
    );
  }
  fail("cannot determine the repository. Pass --repo owner/name or set GITHUB_REPOSITORY.");
}

async function fetchRepoId(repo: string, token: string): Promise<number> {
  const response = await fetch(`https://api.github.com/repos/${repo}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) {
    fail(
      `cannot look up ${repo} (HTTP ${response.status}). ` +
        "Check the repository name and that your token can access it.",
    );
  }
  // REST id is the numeric id the upload endpoint wants (NOT the GraphQL node id).
  const { id } = (await response.json()) as { id: number };
  return id;
}

async function upload(filePath: string, repoId: number, token: string): Promise<string> {
  const name = basename(filePath);
  const mime = contentTypeFor(name);
  if (!mime) fail(`images and video only (png/jpg/gif/webp/mp4/webm): ${filePath}`);
  let bytes: Buffer;
  try {
    bytes = readFileSync(filePath);
  } catch {
    fail(`no such file: ${filePath}`);
  }
  if (!matchesMagic(bytes, mime)) {
    fail(
      `${filePath}: content does not match its extension (expected ${mime}). ` +
        "Refusing to upload a disguised file.",
    );
  }
  const response = await fetch(uploadUrl(name, mime, repoId), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
      "Content-Type": mime, // omitting this 400s before anything else is checked
    },
    body: new Uint8Array(bytes),
  });
  if (response.status !== 201) {
    const detail = (await response.text()).trim();
    fail(`upload of ${name} failed: HTTP ${response.status} ${detail}`);
  }
  const { url } = (await response.json()) as { url: string };
  return markdownFor(name, url);
}

async function main(): Promise<void> {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (err) {
    fail((err as Error).message);
  }
  if (args.files.length === 0) {
    fail("usage: node attach.ts [--repo owner/name] <file> [file ...]");
  }
  const token = resolveToken();
  const repo = resolveRepo(args.repo);
  const repoId = await fetchRepoId(repo, token);
  for (const file of args.files) {
    console.log(await upload(file, repoId, token));
  }
}

await main();
