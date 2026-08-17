# Security Policy

## Reporting

Report suspected vulnerabilities privately via GitHub's **Security → Report a
vulnerability** tab, not as a public issue. If you believe you have found a
backdoor or privilege-escalation path, say so explicitly and it will be
prioritised over everything else.

Please include the release version, the jar's SHA-256, and your server platform
and version.

## What this plugin deliberately does not do

These are guarantees, not aspirations. Each is enforced by a CI check that
fails the build on violation (`.github/workflows/build.yml`, `audit` job):

- **Never grants operator status.** No call to `setOp` anywhere in the source.
- **Never grants permissions at runtime.** No `addAttachment`.
- **Never executes commands on a player's or console's behalf.** No
  `dispatchCommand` or `performCommand`.
- **Makes no network requests.** No HTTP client, no sockets, no telemetry, no
  update checker, no analytics. The plugin does not know the internet exists.
- **Spawns no processes.** No `Runtime.getRuntime`, no `ProcessBuilder`.
- **Loads no code dynamically.** No `Class.forName`, no `defineClass`, no
  `javax.script`, no Base64-decoded payloads.
- **Uses no reflection into server internals.** No `setAccessible`, no NMS. In
  addition to being a security property, this is what allows one source tree to
  target both 1.21.11 and 26.x — from 26.1 Mojang no longer ships obfuscated
  server jars, so Spigot-mapped internals do not work at all.

If a future feature genuinely needs one of the above, the exclusion must be
narrow, documented here with a rationale, and reviewed — not added by widening
the CI pattern.

### Scope of these guarantees

They apply to `strikerload-core` and `strikerload-paper` as shipped today.

They are **not** portable promises. Legacy targets (1.8.8 and similar) have no
PersistentDataContainer and no ray tracing, so item data and targeting there
cannot be implemented without version-specific reflection into server
internals. If a legacy module is ever added, it will be a separate artifact
with its own explicitly narrowed guarantees stated here — the guarantees above
will not silently be weakened to accommodate it.

Folia support introduced no new capability surface: it changed which thread
work runs on, not what the plugin is allowed to touch. Notably it did **not**
introduce runtime platform detection, which conventionally uses
`Class.forName` — the Folia schedulers work on Paper as-is, so no detection is
needed and the `Class.forName` ban stands unmodified.

`strikerload-core` additionally has zero dependencies and no platform imports,
enforced by CI. That means the payload maths can be read and verified in
isolation, independently of any loader.

## Verifying your download

Every release is built by GitHub Actions from a tagged commit. The workflow run
is public and linked in the release notes.

```bash
sha256sum -c SHA256SUMS.txt
```

If the checksum of a jar you obtained elsewhere does not match the one in the
release, do not run it. Report it.

You do not have to take any of this on trust: the source is AGPL-3.0-or-later
and the build is reproducible from it.

## Permissions model

The cannon is destructive by design. Defaults are deliberately restrictive:

- `strikerload.use.*` and every `strikerload.use.<payload>` node default to no access
  for non-ops.
- `worlds:` in `config.yml` should be set to an explicit allowlist before
  granting any use permission.
- The `safety:` block caps TNT and entity counts per strike. These caps exist
  to prevent a mistyped ring count from taking the server down; raise them
  deliberately.

TNT is spawned with `setSource(shooter)` so WorldGuard, CoreProtect and similar
can attribute and cancel it. Strikes dispatched from console have no source and
may bypass region protection — the command warns when this happens.

## Scope

Reports in scope: privilege escalation, unattributed griefing that bypasses
region protection, remote code execution, resource exhaustion reachable without
the relevant permission node.

Out of scope: a player with `strikerload.use.*` blowing up your spawn. That is the
plugin working. Configure your permissions.

## Provenance

This is an independent clean-room reimplementation written from a published
feature and command list. It contains no code from any prior plugin.
