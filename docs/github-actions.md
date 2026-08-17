# GitHub Actions, for someone who builds locally

You already know how to build this: `mvn clean package`. Actions is that same
command, run on a fresh rented Linux machine, triggered by a git push. That's
genuinely the whole idea. Everything below is mechanics.

## Why bother, given you build fine locally

For this project specifically, there is one reason that matters: **your users
cannot verify a jar you built on your own machine.** They have to trust that
the jar matches the source. That is exactly the trust that the previous plugin
broke.

When Actions builds it, the log is public, the commit is public, and the
checksum is published. Anyone can see that the jar came from source they can
read. You are removing yourself as the thing people have to trust.

Secondary benefits: all three profiles get built every time (easy to forget one
locally), and a broken build is caught on push rather than after release.

## One-time setup

```bash
cd strikerload
git init
git add .
git commit -m "Initial commit"
```

Create an empty repo on github.com — **no** README, .gitignore or license, since
you already have all three. Then:

```bash
git remote add origin https://github.com/YOURNAME/strikerload.git
git branch -M main
git push -u origin main
```

That's it. The workflow file at `.github/workflows/build.yml` is already in the
repo, and GitHub picks it up automatically. Go to the **Actions** tab and you
should see a run starting within a few seconds.

No account configuration, no secrets, no billing setup. Public repositories get
unlimited free Actions minutes.

## Reading the workflow file

Open `.github/workflows/build.yml` alongside this. Four concepts:

**`on:`** — what triggers a run. Ours: any push to `main`, any pull request,
any tag starting with `v`, or a manual button.

**`jobs:`** — independent units of work, each on its own fresh machine. Ours has
three: `audit`, `build`, `release`. `needs:` chains them, so `build` only runs
if `audit` passed, and `release` only if `build` passed.

**`steps:`** — commands within a job, top to bottom. First failure stops the job.
`uses:` pulls in a prewritten action (`actions/checkout@v4` clones your repo);
`run:` is a plain shell command.

**`strategy.matrix:`** — runs the same job several times with different values.
Ours runs `build` three times, once per Minecraft target, in parallel. That's
why one push produces all three jars.

## What each job does here

**`audit`** is the important one. It greps `src` for `setOp`, `dispatchCommand`,
HTTP clients, `ProcessBuilder`, `Class.forName`, `setAccessible`, `Base64`. If
any appear, the job fails and no jar is produced. It also fails if a Bukkit or
Minecraft import appears in `strikerload-core`, and if any `.java` file is
missing its AGPL header.

This is the mechanical answer to "how do I know there's no backdoor". It runs
before anything is built, every single time, and it cannot be skipped.

**`build`** runs `mvn clean package -P <profile>` on the right JDK, computes
SHA-256 of each jar, and uploads them as artifacts you can download from the run
page.

**`release`** only runs on a `v*` tag. It collects all three jars plus a
`SHA256SUMS.txt` and attaches them to a GitHub Release.

## Everyday use

Normal work — push and check the Actions tab:

```bash
git add .
git commit -m "Tune nuke ring density"
git push
```

Green check: fine. Red X: click the run, click the failed job, expand the red
step. The error is the last thing before it stops, same as a local build.

Cutting a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Wait a few minutes, then check the **Releases** page. Jars and checksums will be
attached. Nothing else to do.

## Things that will confuse you the first time

**The runner is wiped every run.** Nothing persists between runs or between
jobs. Anything one job needs from another must be uploaded as an artifact. This
is why `release` re-downloads what `build` produced.

**Editing the workflow only takes effect once pushed.** The file on your disk is
irrelevant; GitHub runs the version in the commit.

**Actions can't see your local `~/.m2`.** If it builds locally but fails in CI
with a missing dependency, you almost certainly have something installed locally
that isn't declared in a `pom.xml`. Genuinely useful signal — that's a bug that
would bite any other contributor.

**The workflow file is YAML and indentation-sensitive.** Most first failures are
a misindented key, not your code. GitHub shows a syntax error at the top of the
run.

**A failed run is free and harmless.** Nothing is published, nothing is broken.
Push a fix and it runs again. Do not be precious about it.

## Testing workflow changes without spamming commits

Push to a branch instead of `main`:

```bash
git checkout -b ci-test
git push -u origin ci-test
```

The `on: push` trigger is limited to `main`, so add your branch temporarily, or
use the **Run workflow** button (that's what `workflow_dispatch` is for).

There's also [`act`](https://github.com/nektos/act), which runs workflows locally
in Docker. Handy given how you like to work, though it doesn't emulate
everything — treat a green `act` run as encouraging, not conclusive.

## What to do first

1. Push the repo.
2. Watch the first run and let it fail if it fails — the Maven profiles have
   not been built against the real Paper APIs yet, so expect to fix something.
3. Only tag `v1.0.0` once all three matrix jobs are green.

Then the audit gate is running on every commit, forever, without you thinking
about it. That's the part worth having.
