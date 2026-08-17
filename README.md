# StrikerLoad

Orbital strike cannon for Minecraft servers. TNT-based payloads dropped from
the build limit, right-click targeting, per-payload permissions.

Independent clean-room reimplementation written from a published feature and
command list. Contains no code from any prior plugin.

Community software. Not affiliated with PaperMC, SpigotMC or Mojang.

## Layout

```
strikerload-core     platform-independent maths and state; zero dependencies
strikerload-bukkit   Paper/Spigot/Purpur module
strikerload-fabric   planned; will consume core unchanged
```

`strikerload-core` holds ring geometry, column bounds, falloff and cooldown
state. It imports nothing from Bukkit, Minecraft, Fabric or Adventure, and CI
fails the build if that ever changes. Adding a loader means writing an adapter,
not forking the payload logic.

## Building

```bash
mvn clean package                 # 26.2   (default, JDK 25)
mvn clean package -P mc26_1       # 26.1.x (JDK 25)
mvn clean package -P mc1_21_11    # 1.21.11 (JDK 21)
```

Jars land in `strikerload-bukkit/target/`.

| Target   | paper-api                | api-version | JDK |
|----------|--------------------------|-------------|-----|
| 26.2     | `[26.2.build,)`          | `26.2`      | 25  |
| 26.1.x   | `[26.1.2.build,)`        | `26.1`      | 25  |
| 1.21.11  | `1.21.11-R0.1-SNAPSHOT`  | `1.21`      | 21  |

Three jars rather than one: 26.1+ requires Java 25, and Java 25 bytecode throws
`UnsupportedClassVersionError` on 1.21.11's Java 21 runtime. Version ranges keep
`.build` in them deliberately — `[26.1,)` can resolve across a breaking drop.

## Folia

Supported on all three targets, from the same jars — no separate Folia build.
The Folia schedulers are internally handled on ordinary Paper, so one code path
serves both and there is no runtime platform detection anywhere in the source.

What that required, since Folia has no main thread and regions tick in parallel
without sharing data:

- Every charge is scheduled onto the region that owns **its own** drop
  position, not the strike centre. A wide nuke genuinely spans several regions.
- Each TNT charge watches itself for impact via its own entity scheduler.
  There is no shared list, because a shared list would be mutated from several
  region threads at once.
- Wolf target selection runs on the wolf's scheduler over region-local entities
  only, and darkness is applied on each player's own scheduler.
- Stasis teleports go through `teleportAsync`.
- Config is snapshotted into an immutable `Settings` object on load and reload,
  so region threads never read `FileConfiguration` while it is being replaced.
- Cooldown state is a `ConcurrentHashMap`.

Folia is not a drop-in upgrade and is worth running only for high player counts
spread across the world. Most servers should stay on Paper. Test on Folia
before trusting it in production — the payload code is written to the rules
above, but regionised threading is unforgiving and I would not call it
battle-tested until you have.

## Server prerequisite

Payloads spawn hundreds of TNT entities in a single tick. In `spigot.yml`:

```yaml
max-tnt-per-tick: 1000000
```

Without it most of the payload silently never primes. Purpur and Pufferfish
have their own TNT throttles too.

## Payloads

| Payload    | Size argument | Effect |
|------------|---------------|--------|
| `nuke`     | ring count    | Concentric TNT rings from build limit, detonating on impact |
| `stab`     | —             | Static TNT column bedrock to sky, detonated top-down |
| `railgun`  | arrow count   | Delayed volley of high-damage piercing arrows |
| `wolf`     | wolf count    | Airdrops angry wolves onto the nearest non-caster |
| `darkness` | block radius  | Darkness to everyone in range except the caster |
| `stasis`   | —             | Delayed teleport of the caster to the painted point |

## Commands

```
/strikerload give <payload> [player] [size]
/strikerload strike <payload> <x> <y> <z> [world] [size]
/strikerload reload
```

Aliases: `/sl`, `/strikeload`

## Permissions

- `strikerload.admin` — the command (op)
- `strikerload.use.*` — all payloads (op)
- `strikerload.use.<payload>` — per-payload grants (default deny)
- `strikerload.bypass.cooldown` (default deny)

Set `worlds:` in `config.yml` to an explicit allowlist before granting use to
anyone. The `safety:` block caps entities per strike; those caps are the
difference between a fun payload and a server that does not come back.

## Roadmap

1. Bukkit module — covers CraftBukkit, Spigot and Paper from one jar
2. Additional Minecraft versions
3. Fabric module
4. Legacy versions (1.8.8) — see the scope note in SECURITY.md; these cannot
   meet the current no-reflection guarantee and would ship as a separately
   scoped artifact

Forge is not planned.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).

You may use, modify, redistribute and sell this, including commercially. You
must keep it open: any modified version you distribute, or that players
interact with over a network, must be offered under the same license with
source available (section 13).

AGPL does not prohibit charging money — no open source license does. What it
prohibits is closing the source, which is the property that matters here.

## Contributing / CI

New to GitHub Actions? See [docs/github-actions.md](docs/github-actions.md).

## Security

See [SECURITY.md](SECURITY.md) for what this plugin is guaranteed not to do —
no op-granting, no network access, no dynamic code loading — and how each
guarantee is enforced by a blocking CI gate.

Releases are built by GitHub Actions from a tagged commit with published
checksums:

```bash
sha256sum -c SHA256SUMS.txt
```
