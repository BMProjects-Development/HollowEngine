# HollowEngine addons

Every directory below `addons/` that contains a `build.gradle.kts` is discovered automatically as a Gradle subproject. Addon builds receive Minecraft, mappings, HollowEngine runtime, Kotlin, coroutines, and Koin on their compile classpath.

Build every addon with:

```shell
./gradlew buildAddons
```

The resulting jars are collected in `build/addon-jars/`. One jar works with both mod loaders.

Copy the jar to either of these directories:

- `mods/` - the usual choice for distributed addons and modpacks;
- `hollowengine/addons/`- useful during development or when you want to keep addons separate from mods.

The runtime scans and watches both directories. If the same addon id is present in both at startup, the newest version wins; when the versions are equal, the copy in `hollowengine/addons/` has priority. Do not keep duplicate copies unless you are deliberately testing version selection.

Runtime diagnostics and lifecycle controls are available to operators:

```text
/he addons list
/he addons enable <addon-id>
/he addons disable <addon-id>
/he addons reload <addon-id>
```

Disabled ids are persisted in `hollowengine/addons/.disabled-addons`. The `debug-command` example has no bootstrap libraries and can therefore be copied and loaded while Minecraft is running. It directly handles `RegisterCommandsEvent` and adds `/he addon-text <text>`.

Command addons use Brigadier directly from `@SubscribeEvent`. `RegisterCommandsEvent` fires on every datapack load, and enabling, disabling or reloading an addon reloads the datapacks of every running server, so the command tree is rebuilt with the addon's commands added or gone. The video addon demonstrates the same mechanism with `/he video <local-path-or-url>`.

An addon's `build.gradle.kts` only needs its own settings and libraries. Dependencies added to `addonLibraries` are available during compilation, embedded as nested jars, and loaded in the addon's isolated classloader. Pure Java runtime-only libraries belong in `addonRuntimeLibraries`.

Libraries that load native code or keep process-global state belong in `addonBootstrapLibraries`. They are loaded into HollowEngine's stable runtime classloader before addon initialization. A newly copied or updated addon that contains bootstrap libraries is deliberately not hot-loaded: `HollowAddonManager.restartRequired` reports it, the log asks for a restart, and it becomes available on the next game launch.

```kotlin
base.archivesName.set("MyAddon")

dependencies {
    add("addonLibraries", "com.example:library:1.0.0")
    add("addonRuntimeLibraries", "com.example:pure-java-runtime-library:1.0.0")
    add("addonBootstrapLibraries", "com.example:native-library:1.0.0:windows-x86_64")
}
```

Do not bundle Minecraft-owned native stacks such as LWJGL, jemalloc, GLFW, OpenAL, OpenGL, STB, Vulkan, JNA, JInput, Netty, or OSHI. Addon builds reject them and the bootstrap validates external addon jars before loading. The game-provided versions must be used.

Declare the addon in `src/main/resources/META-INF/plugin.properties`:

```properties
id=my-addon
name=My Addon
version=${version}
entry=com.example.myaddon.MyAddon
dependsOn=another-addon
environment=common
description=What the addon does
authors=Me, Someone Else
license=MIT
icon=assets/my-addon/icon.png
```

`entry` may be left out by an addon made only of scripts and resources. `description`, `authors`, `license` and `icon` go into the `fabric.mod.json` and `neoforge.mods.toml` the build generates, which is what mod lists show.

## Assets and data

An addon carries `assets/` and `data/` in `src/main/resources` exactly like a mod, and the build adds the `pack.mcmeta` both loaders need. From `mods` the loader serves them. From `hollowengine/addons` the engine does, below the `hollowengine` folder's own resources, so the project can still override anything an addon ships. Enabling, disabling or reloading such an addon reloads the datapacks and, when it has assets, the client's resources.

Disabling an addon that lives in `mods` stops its code and scripts, but not its resources: those belong to the loader, which keeps serving them until the jar is removed.

Entrypoints receive a lifecycle `CoroutineScope`. Public `@SubscribeEvent` methods declared on the entrypoint, a Kotlin `object`, or as static/top-level functions are discovered automatically. HollowEngine registers them in that scope; cancelling the scope during unload removes all of them.

```kotlin
class MyAddon : HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        // Start addon coroutines and publish services here.
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.Server) {
        // Handle the event synchronously.
    }
}
```

## Scripts

An addon can ship `.kts` scripts of its own in `src/main/resources/scripts`. The build compiles them with the same compiler the game uses and packs both the sources and the compiled artifacts into the addon jar; the remap table covers the compiled scripts too, so they run in a modpack that never installs the compiler addon. A compilation error fails the build. `debug-command` carries one as an example.

Scripts belong to the namespace named by the addon's `id`, and are addressed with it everywhere a script path is accepted:

```text
/he scripting run my-addon:nodes/quest.node.kts
```

The `hollowengine` directory is the same kind of thing - an unpacked addon. Its scripts live in `hollowengine/scripts` and its namespace comes from an optional `hollowengine/META-INF/plugin.properties`, defaulting to `hollowengine-sandbox`, which addons may not claim. Paths written without a namespace always mean that directory, so existing world saves and commands keep working whatever it calls itself.

Scripts compile against the classpath of the namespace that owns them and run under its classloader, so an addon's scripts see the addon's own classes and its `addonLibraries`. `@file:Import("other-addon:shared.kts")` reaches into another namespace and requires it in `dependsOn`; a plain name is resolved next to the importing script.

Enabling, reloading or disabling an addon starts and stops its scripts with it. A disabled addon's nodes keep the state they were stopped with, and resume from it when it comes back.

Compiled scripts are also cached at runtime, in `hollowengine/cache/scripts`, keyed by the sources, the engine build, the Kotlin and Minecraft versions and the mapping namespace. Fill the cache for a whole pack before shipping it with:

```text
/he scripting compile
```

If a cached or shipped artifact no longer matches its sources and no compiler is installed, it is used anyway and the log says so - a modpack without the compiler has nothing better to fall back on.

To ship compiled scripts without their sources, build the addon with:

```shell
./gradlew buildAddons -Phollowengine.scripts.includeSources=false
```

## Exporting the `hollowengine` folder

The project panel of the in-game editor turns the `hollowengine` folder into the same kind of jar: its scripts, compiled as above, its `assets/`, `data/` and `META-INF/plugin.properties`. The folder needs an id of its own for that, set in the project settings; `hollowengine-sandbox` cannot be exported. An export made with its sources can be imported back into the folder of another game, replacing the project there after zipping it into `hollowengine/backups`.
