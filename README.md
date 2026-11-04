# AutoRaid

A [RusherHack](https://rusherhack.org) plugin that automatically drinks an Ominous Bottle when the raid victory boss bar appears, so you can chain raids on a raid farm without touching the keyboard.

## Features

When a raid is won, the `AutoRaid` module (category: Player):

1. Detects the victory boss bar.
2. Moves an Ominous Bottle onto your hotbar if there isn't one already.
3. Drinks exactly one bottle (it releases the use key the moment one is consumed, so vanilla never starts a second drink).
4. Restores the slot you were holding before.

### Settings

| Setting          | Default | Description                                                                        |
|------------------|---------|------------------------------------------------------------------------------------|
| `HotbarSlot`     | 9       | Hotbar slot (1-9) a bottle is moved into when none is on the hotbar.               |
| `Delay`          | 10      | Ticks to wait after victory before drinking (20 ticks = 1 second).                 |
| `SkipIfOmen`     | on      | Don't drink if Bad Omen or Raid Omen is already active.                            |
| `AutoDisconnect` | off     | Disconnect when no Ominous Bottles are left.                                       |
| `TextDetection`  | on      | Also detect victory from the boss bar text, for servers that strip translation keys. |
| `Notifications`  | on      | Print chat messages when the module acts.                                          |

## Supported versions

| Minecraft | Module    | Mappings              |
|-----------|-----------|-----------------------|
| 1.21.4    | `1.21.4/` | Mojmap + Parchment    |
| 1.21.11   | `1.21.11/`| Mojmap + Parchment    |
| 26.2      | `26.2/`   | none (unobfuscated)   |

Each version is built as its own jar: `auto-raid-<minecraft version>-<plugin version>.jar`.

## Installing

1. Build the jar for your Minecraft version (see below).
2. Drop it into your RusherHack plugins folder (`.minecraft/rusherhack/plugins`).
3. Start the game and enable `AutoRaid` in the RusherHack GUI.

## Project layout

This is a multi-project Gradle build.

```
common/        shared plugin code and resources, compiled into every version
  src/main/java/org/autoraid/   AutoRaidPlugin, AutoRaidModule
  src/main/resources/           rusherhack-plugin.json
<version>/     one folder per Minecraft version
  build.gradle, gradle.properties
  src/main/java/org/autoraid/VersionCompat.java   per-version API differences
tools/         make_void_world.py, test world generator
```

Almost everything lives in `common/`. A version module only contains its build setup and `VersionCompat`, a tiny class that hides API differences between Minecraft versions (for example how the selected hotbar slot is read and written).

To support a new Minecraft version: copy the closest existing module folder, update its `gradle.properties` (Minecraft version, loader version, mappings), add `include '<version>'` to `settings.gradle`, and fix anything that no longer compiles in `VersionCompat`. The RusherHack API `<version>-SNAPSHOT` must exist on the [RusherHack Maven](https://maven.rusherhack.org/snapshots).

## Building

Requires **JDK 25**. The Gradle wrapper (9.8.2) is included.

```sh
./gradlew build                  # every version
./gradlew :1.21.11:build         # one version
```

Jars end up in `<version>/build/libs/`.

The first time (and after changing the Minecraft version) run `./gradlew :<version>:updateAccessWidener` so the RusherHack access widener is fetched.

## Running in a dev client

1. Copy `rusherhack-loader.jar` into `<version>/lib/`. It is gitignored.
2. Run:

```sh
./gradlew :1.21.11:runPlugin
./gradlew :1.21.11:runPlugin -PquickPlay="Void Raid"   # join a singleplayer world directly
```

### Void test world

`tools/make_void_world.py` builds a void world containing a raid farm schematic, with a creative player holding a lot of Ominous Bottles and a netherite sword. It uses only the Python standard library:

```sh
python3 tools/make_void_world.py <farm.litematic> <version>/run/saves <version>
# e.g. python3 tools/make_void_world.py farm.litematic 26.2/run/saves 26.2
```

Supported versions are `1.21.4`, `1.21.11` and `26.2`. The world is named `Void Raid`. Block entities (chests, hoppers, signs) are kept; entities are dropped. A schematic is not included in this repository.

## License

See [LICENSE](LICENSE).
