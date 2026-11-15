# HollowEngine addons

Every directory below `addons/` that contains a `build.gradle.kts` is discovered automatically as a Gradle subproject. Addon builds receive Minecraft, mappings, HollowEngine runtime, Kotlin, coroutines, and Koin on their compile classpath.

Build every addon with:

```shell
./gradlew buildAddons
```

The resulting jars are collected in `build/addon-jars/`. An addon that uses only Minecraft and the engine works with both mod loaders from one jar; see "Mod loaders" below for the rest.

Copy the jar to either of these directories:

- `mods/` - the usual choice for distributed addons and modpacks;
- `hollowengine/addons/`- useful during development or when you want to keep addons separate from mods.

In a development run of this repository nothing has to be copied: `runClient` and `runServer` build every addon first and pass `build/addon-jars` to the game with `-Dhollowengine.devAddons`. That directory is looked at before the two others, so a copy of the same addon left in `mods/` never wins, and it is watched like them: run `./gradlew buildAddons` while the game is open and the rebuilt addons are reloaded in place. Addons with bootstrap libraries still need a restart.

The runtime scans and watches both directories. When several copies of one addon are installed, those made for the running loader and side compete: the newest version wins, and between equal versions the copy from the directory looked at first. The rule is the same at startup and while the game runs: a newer copy dropped in takes over, removing the running copy starts the one that is left, and the copies that lost are listed as `duplicate`.

Runtime diagnostics and lifecycle controls are available to operators:

```text
/he addons list
/he addons info <addon-id>
/he addons enable <addon-id>
/he addons disable <addon-id>
/he addons reload <addon-id>
```

`list` gives every installed addon jar with its state and, unless it simply runs, the reason: `disabled`, `inactive` (made for the other side), `incompatible` (made for the other loader), `missing mods`, `waiting for dependencies`, `restart required`, `rejected`, `failed` or `duplicate`. The same is available to code as `HollowAddonManager.statuses`.

Disabled ids are persisted in `hollowengine/addons/.disabled-addons`. An addon without bootstrap libraries can be copied and loaded while Minecraft is running; a jar is read once it has stopped changing. If an updated addon cannot start, the version that was running stays.

Command addons use Brigadier directly from `@SubscribeEvent`. `RegisterCommandsEvent` fires on every datapack load, and enabling, disabling or reloading an addon reloads the datapacks of every running server, so the command tree is rebuilt with the addon's commands added or gone. The video addon demonstrates the same mechanism with `/he video <local-path-or-url>`.

An addon's `build.gradle.kts` only needs its own settings and libraries. Dependencies added to `addonLibraries` are available during compilation, embedded as nested jars, and loaded in the addon's isolated classloader. Pure Java runtime-only libraries belong in `addonRuntimeLibraries`.

Libraries that load native code or keep process-global state belong in `addonBootstrapLibraries`. They are loaded into HollowEngine's stable runtime classloader before addon initialization. A newly copied or updated addon that contains bootstrap libraries is deliberately not hot-loaded: `HollowAddonManager.restartRequired` reports it, the log asks for a restart, and it becomes available on the next game launch. The libraries of an addon that is switched off, or made for the other loader or side, are not loaded at all.

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
dependsOn=another-addon>=1.2
dependsOnMods=some-mod, optional-mod?
environment=common
platform=common
description=What the addon does
authors=Me, Someone Else
license=MIT
icon=assets/my-addon/icon.png
```

`entry` may be left out by an addon made only of scripts and resources. The mods in `dependsOnMods` must be installed for the addon to load, and its scripts compile against them; one written with a `?` is optional. An id in `dependsOn` and `dependsOnMods` may be followed by the versions it has to have, as in `library>=1.2` or `core>=2.0<3.0` (`>=`, `>`, `<=`, `<`, `=`, `!=`): an addon whose dependency is of another version waits for it, and `/he addons list` names the installed one. `description`, `authors`, `license` and `icon` go into the `fabric.mod.json` and `neoforge.mods.toml` the build generates, which is what mod lists show.

## Mod loaders

`platform` says which loaders the addon is written for: `common` (the default) knows no loader, uses only Minecraft and the engine and runs on Fabric and NeoForge from one jar; `fabric` and `neoforge` use that loader's API and run only there; `fabric,neoforge` uses both, each in its own part, and runs on both from one jar.

The sources follow the same split. `src/main` is common code and never sees a loader's API, in any addon. Code that needs one goes to `src/fabric` or `src/neoforge`, which see `src/main` and the API of that loader. A `common` addon has neither directory, and the build refuses one; a `fabric,neoforge` addon may have either or both: the jar carries each part apart from the rest and the game opens only the one made for its loader. A `fabric` or `neoforge` addon has only its own directory and gets only that loader's mod descriptor. Resources always stay in `src/main/resources`.

In an addon that names loaders, `entry`, `dependsOn`, `dependsOnMods` and `requiredClasses` can be given for one of them by adding its name: `entry.neoforge` replaces `entry` on NeoForge, and for the lists the loader's values are added to the common ones.

```properties
platform=fabric,neoforge
entry=com.example.myaddon.MyAddon
entry.neoforge=com.example.myaddon.neoforge.MyNeoForgeAddon
dependsOnMods.fabric=fabric-api
```

## Addons inside a mod

An integration between a mod and the engine can live in that mod's own jar instead of a separate one. It is declared in `META-INF/hollowengine/mod-addon.properties` with a `hostModId`, compiled against the development jar (`./gradlew developmentJar`), and loaded from the mod's own jar. The package of its entrypoint is defined next to the engine, because the mod loader cannot see the engine's classes; everything else, the mod's own classes included, still comes from the mod loader, so the addon may call into the mod but the mod must not reference the addon. It follows the mod's lifecycle: it cannot be disabled or reloaded at runtime and has no bundled libraries. [`template`](template/README.md) is a ready-made starting point.

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

An addon can ship `.kts` scripts of its own in `src/main/resources/scripts`. The build compiles them with the same compiler the game uses and packs both the sources and the compiled artifacts into the addon jar; the remap table covers the compiled scripts too, so they run in a modpack that never installs the compiler addon. A compilation error fails the build.

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
