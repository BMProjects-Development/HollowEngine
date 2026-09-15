# HollowEngineRuntime.jar - what this file is

Short explanation for reviewers: This embedded JAR file is not a third-party library included in the project and does
not contain obfuscated code. It is HollowEngine’s own source code, compiled from
a publicly available repository and saved as a separate JAR file, since it must be loaded via an isolated `ClassLoader`.

* Source code: https://github.com/HollowHorizon/HollowEngine
* License: see `LICENSE.MD` in the repository
* Contact: https://discord.gg/qKpPhkwGCY

## Why does the mod have its own code included as an embedded JAR file?

HollowEngine is written in Kotlin 2.4 and includes a full-featured Kotlin compiler (the in-game
IDE and scripting system compile user scripts at runtime). In NeoForge and
Fabric, mods use the same class loader, so these classes will conflict with any
other mods that use Kotlin: Kotlin For Forge, Kotlin Lang Forge, Essential,
and others.

To avoid that, the mod is split in two:

* the small **bootstrap** part you see in the outer jar, which only sets up a loader.
* **HollowEngineRuntime.jar**, the actual engine, loaded into a private,
  isolated `ClassLoader` so its Kotlin (and every other dependency it carries)
  is invisible to the rest of the game.

This is the same concept as a shaded/relocated dependency, done at class loader
level instead, because a [Kotlin Compiler Addon](https://github.com/HollowHorizon/HollowEngine/releases/tag/v2.3.0) cannot use relocated Kotlin library.

## How to verify it?

* The jar is **not obfuscated or minified** - open it with any decompiler
  (Vineflower, CFR, JD-GUI) and the class names, methods and structure match the
  public repository.
* `HollowEngineRuntime.sha256` next to this file holds the SHA-256 of the
  payload, so it can be checked against a jar built from source.
* A build from a clean checkout reproduces it:

```
git clone https://github.com/HollowHorizon/HollowEngine
cd HollowEngine
./gradlew build
```

The nested jar is produced by the `embedRuntimeJar` task in
`bootstrap-fabric/build.gradle.kts` / `bootstrap-neoforge/build.gradle.kts`,
which copies the output of the `:runtime` subproject into
`META-INF/hollowengine/runtime/`.

## Other nested jars

The same jar is a mod for both NeoForge and Fabric: it carries `META-INF/neoforge.mods.toml` and
`fabric.mod.json`, and each loader reads only its own descriptor.

`META-INF/jars/` holds unmodified upstream libraries, bundled through each loader's standard jar-in-jar, so
players do not have to install them separately. Each loader loads only the jars its own descriptor lists:
`"jars"` in `fabric.mod.json` for Fabric, `META-INF/jarjar/metadata.json` for NeoForge.

* **Fabric API** (Apache 2.0, https://github.com/FabricMC/fabric), Fabric only. If a newer Fabric API is
  installed, Fabric Loader uses that one instead.
* **MixinExtras** (MIT, https://github.com/LlamaLad7/MixinExtras), a Fabric and a NeoForge build.

## What happens at runtime?

On startup, the bootstrap reads the checksum next to this file, extracts the
payload into the game directory (`hollowengine/.cache/runtime/`) under a
name based on the checksum, and loads it into the isolated loader. The bootstrap performs
no network access at all: the runtime is taken from inside the mod JAR and is never downloaded.
