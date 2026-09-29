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

Worktrees and builds go under `.claude/worktrees/`, which git ignores. They do
not go in a `/tmp` scratchpad: a worktree's `node_modules` and Maven build
overflow its per-user quota. Images and throwaway specs can go in the
scratchpad (`$S`).

```bash
W=.claude/worktrees
# after: this branch
(cd src/main/frontend && pnpm build-storybook -o ../../../$W/sb-after --quiet)
# before: main, in a worktree (never switch the branch you are working in)
git worktree add $W/shots-main origin/main
(cd $W/shots-main/src/main/frontend && pnpm install --frozen-lockfile --prefer-offline \
  && pnpm build-storybook -o ../../../../sb-before --quiet)

node .claude/skills/ui-screenshots/scripts/capture-stories.mjs --storybook $W/sb-before --out $S/shots --prefix before <story-id> ...
node .claude/skills/ui-screenshots/scripts/capture-stories.mjs --storybook $W/sb-after  --out $S/shots --prefix after  <story-id> ...
git worktree remove $W/shots-main && rm -rf $W/sb-before $W/sb-after
```

- **Story ids** are `title--export` in kebab case (`Snapshots/SourceView` and
  `Marked` give `snapshots-sourceview--marked`). A static build lists them in
  `index.json`.
- **Options:** `--widths 1280,390` and `--themes light,dark` are the defaults.
  The script shoots the story root only, after its play function has run.
- **It fails closed.** An unknown id, a render error or an empty root is an
  error, not a blank image.
- **No story shows the same data on both sides?** Write a throwaway story in
  `$S` that renders the component with the data the claim needs. Copy it
  into both trees' `src/components/` before building, and delete it
  afterwards. On `main`, a prop it does not know yet is ignored, so the
  before shows exactly what `main` shows for that data. Never commit it.
- `--frontend <dir>` names the frontend whose `node_modules` holds
  Playwright, when you run the script from outside the checkout that has it.

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
   Wait for each step as the real specs do. For example, after registering,
   wait for the dialog to close and the marketplace heading to show before
   ingesting.
2. **After:** repackage the jar (`./mvnw -q package -DskipTests`; e2e runs
   the newest jar in `target/`). Copy the spec into `src/main/frontend/e2e/`,
   run `SHOT_DIR=$S/shots/after pnpm e2e <spec-name>`, and delete the copy.
   Pass no `--`: under pnpm 12 it is passed through, and the whole suite runs.
3. **Before:** the same in a `main` worktree under `.claude/worktrees/`.
   Package there, then run its own `pnpm e2e`, which uses that worktree's jar.

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
