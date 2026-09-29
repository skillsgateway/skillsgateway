---
name: ui-screenshots
description: Capture before/after screenshots of the Skills Gateway portal and put them in the PR body. Use whenever a change touches src/main/frontend/src/ (any portal page, component or style); the pr-screenshots check fails such a PR until its body carries an image or the no-screenshots label. Also use when asked to screenshot the portal or illustrate a UI change.
---

# Before/after screenshots for portal changes

A portal change described only in prose cannot be reviewed without building the
branch. Every PR that changes `src/main/frontend/src/` carries before/after
images **in its PR body**, as a two-column table. The `pr-screenshots` check
enforces it. Test files, stories, MSW fixtures and the generated API types do
not count as a portal change.

The check stays red until the images are in the body. That is expected while
you wait for the owner to approve the upload. It is not a failure to fix
another way. A change with nothing visible (a type fix, a refactor with the
same output) takes the `no-screenshots` label, and applying it is a decision,
not a way to silence the check.

## Photograph the claim

- **The after shot shows what the PR says changed, and the before shot
  visibly lacks it.** A screenshot that shows the page but not the change is
  not evidence.
- **Each pair is identical except for the change**: the same story or page,
  the same size, the same theme and the same data.
- **Fixture data only.** Storybook stories and the e2e fixtures are
  throwaway data, and nothing else goes in frame: no real marketplace, no
  identity, no token, and nothing from an employer. The repository is public,
  and an upload cannot be deleted.
- **Light and dark** when colour or contrast is part of the change.
  **Desktop (1280) and mobile (390)** when layout is.

## Recipe A: component states, from Storybook

Use this when the change shows in a story, whether an existing one or one this
PR adds. Stories enumerate the states and hold fixture data.

```bash
S=<scratch dir>   # your session scratchpad, never ~/cloud and never the repo
# after: this branch
(cd src/main/frontend && pnpm build-storybook -o $S/sb-after --quiet)
# before: main, in a worktree (never switch the branch you are working in)
git worktree add $S/main-tree origin/main
(cd $S/main-tree/src/main/frontend && pnpm install --frozen-lockfile --prefer-offline && pnpm build-storybook -o $S/sb-before --quiet)

node .claude/skills/ui-screenshots/scripts/capture-stories.mjs --storybook $S/sb-before --out $S/shots --prefix before <story-id> ...
node .claude/skills/ui-screenshots/scripts/capture-stories.mjs --storybook $S/sb-after  --out $S/shots --prefix after  <story-id> ...
git worktree remove $S/main-tree
```

- **Story ids** are `title--export` in kebab case (`Snapshots/SourceView` and
  `Marked` give `snapshots-sourceview--marked`). A static build lists them in
  `index.json`.
- **Options:** `--widths 1280,390` and `--themes light,dark` are the defaults.
  The script shoots the story root only, after its play function has run.
- **It fails closed.** An unknown id, a render error or an empty root is an
  error, not a blank image.
- **A story new in this PR has no before.** Pair it with the closest thing
  `main` has: the same component's nearest story, or the page shot from
  recipe B.

## Recipe B: page flows, against the real jar

Use this for anything a single story cannot show: the page shell, navigation,
a flow across tabs, or data from the real API. It uses the e2e harness (Docker,
PostgreSQL, the mock IdP) and its fixture upstreams.

1. Write a throwaway Playwright spec in `$S`, not in the repo. Log in and
   reach the state as the existing specs do (`e2e/portal.spec.ts` has
   `login`, `registerTainted` and `ingestOnReview`). Then
   `await page.screenshot({ path: process.env.SHOT_DIR + "/<name>.png" })`,
   or `locator.screenshot` for one region. Take nothing that is not fixture
   data.
2. **After:** repackage the jar (`./mvnw -q package -DskipTests`; e2e runs
   the newest jar in `target/`). Copy the spec into `src/main/frontend/e2e/`,
   run `SHOT_DIR=$S/shots/after pnpm e2e -- <spec>.spec.ts`, and delete the
   copy.
3. **Before:** the same in a `main` worktree. Package there, then run its own
   `pnpm e2e`, which uses that worktree's jar.

Never commit the spec or the images.

## Put them in the PR

1. **Show the owner before uploading.** Offer to open the files
   (`xdg-open` on Linux, `open` on macOS, `start` on Windows). Reading an
   image renders it for you, not for them. Get an explicit yes. Uploads are
   permanent.
2. Upload with the `github-upload` skill
   (`node .claude/skills/github-upload/scripts/attach.ts <files>`). It prints
   one markdown image line per file.
3. Put them in the PR body as a table, one row per claim, and save with
   `gh pr edit <n> --body-file <file>`:

   ```markdown
   ## Screenshots

   | Before | After |
   | --- | --- |
   | ![before: …](…) | ![after: …](…) |
   ```

   The check re-runs when the body is edited.

Never commit screenshots to a branch and never use release assets: see
`github-upload` for why.
