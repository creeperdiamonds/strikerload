# strikerload-fabric

The Fabric build of StrikerLoad, for Minecraft **26.2**, **26.1.x** and
**1.21.11**. Server-side only, and it needs Fabric API.

`strikerload-core` (the payload maths) is shared with the Paper build
unchanged. Only the platform layer is new.

## Why this is a Gradle build and not another Maven profile

Fabric Loom is a Gradle plugin with no Maven equivalent, so this module cannot
live in the Maven reactor the way `strikerload-paper` does. It is a separate
Gradle build with one subproject per Minecraft generation, and
`strikerload-core/src/main/java` is compiled straight in rather than published
to a repository first - same source of truth, no publish step.

## The two toolchains

Minecraft stopped being obfuscated in the 26.x series. That is not a cosmetic
change; it splits the toolchain in half:

| Target | Loom plugin | Mappings | Dependencies | Java |
|---|---|---|---|---|
| 1.21.11 | `net.fabricmc.fabric-loom-remap` | `officialMojangMappings()` | `modImplementation` | 21 |
| 26.1.2 | `net.fabricmc.fabric-loom` | none | `implementation` | 25 |
| 26.2 | `net.fabricmc.fabric-loom` | none | `implementation` | 25 |

You can confirm it from Mojang's own version manifest: 1.21.11 publishes
`server_mappings`, and 26.x publishes no mappings at all. This is also why
Yarn stops at 1.21.11 and why Fabric's intermediary for 26.x is a `0.0.0` stub
- there is nothing left to map.

Build a single target, or all three:

```bash
cd strikerload-fabric
./gradlew :mc26.2:build
./gradlew build
```

Jars land in `targets/<target>/build/libs/`.

## One source tree

Despite the toolchain split, all three targets compile the *same* Java. The
Minecraft classes this mod touches agree across the three versions, so the
platform layer is not forked per target.

There is exactly one exception, in `PayloadDelivery#wolfType()`. 1.21.11 and
26.1 both have `EntityType.WOLF`, but 26.2 removed the constant when wolves
became variant-driven. The registry id `minecraft:wolf` is stable across all
three, so the type is looked up rather than referenced. If a future version
breaks something the same way, prefer that trick over forking the file.

## How it differs from the Paper build

These are behaviour differences a server owner will notice, not
implementation trivia.

**Permissions are operator level, not permission nodes.** The plugin has
`strikerload.use.nuke`, `strikerload.bypass.cooldown` and friends, because
Bukkit ships a permission API. Vanilla has only operator levels, so both
firing a payload and running `/strikerload` gate on the level vanilla uses for
`/summon` and `/setblock`. The per-payload split and the cooldown bypass do
not currently exist here. Wiring in a permission mod would restore them and is
the obvious next step if you need finer control.

**Config is JSON, not YAML.** `config/strikerload.json`, created with defaults
on first start. Field names and defaults mirror the plugin's `config.yml`, so
the knobs are the same ones; `worlds` takes dimension ids such as
`minecraft:overworld` rather than Bukkit world names. A malformed file is
logged and ignored in favour of defaults rather than stopping the server.

**No Folia handling, because Fabric does not need it.** The Paper module goes
to real trouble over region threads - region schedulers, per-entity
schedulers, async teleports. A Fabric server ticks on one thread, so this
module spawns directly and uses `TickScheduler` (driven from
`END_SERVER_TICK`) for anything deferred. `CooldownTracker` is still the
concurrent one from core; that costs nothing here.

**`/strikerload` is a Brigadier tree.** Coordinates, player selectors and
yields are parsed and validated by the game, and tab-completion comes for
free. `/sl` is aliased to it. The `strikeload` alias from `plugin.yml` is not
carried over.

**Payload data lives in a `CUSTOM_DATA` component** rather than a
PersistentDataContainer, under `strikerload_`-prefixed keys.
