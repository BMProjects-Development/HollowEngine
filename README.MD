<p align="center">
  <img src="https://raw.githubusercontent.com/HollowHorizon/HollowEngine/refs/heads/dev/docs/assets/titles/welcome.webp"
       style="background-color: #110E11; padding: 0 20%; width: 100%; box-sizing: border-box;" alt="Logo">
  <h1 align="center">Hollow Engine</h1>
</p>

<p align="center">
  <a href="https://github.com/HollowHorizon/HollowEngine/commits/"><img src="https://img.shields.io/github/commit-activity/t/HollowHorizon/HollowEngine?style=for-the-badge&labelColor=a39774&color=ccac4b" alt="Commits"></a>
  <a href="https://github.com/HollowHorizon/HollowEngine/stargazers"><img src="https://img.shields.io/github/stars/HollowHorizon/HollowEngine.svg?style=for-the-badge&labelColor=a39774&color=ccac4b" alt="Stars"></a>
  <a href="https://github.com/HollowHorizon/HollowEngine/graphs/contributors"><img src="https://img.shields.io/github/contributors/HollowHorizon/HollowEngine.svg?style=for-the-badge&labelColor=a39774&color=ccac4b" alt="Contributors"></a>
  <a href="https://github.com/HollowHorizon/HollowEngine/releases"><img src="https://img.shields.io/github/downloads/HollowHorizon/HollowEngine/total?style=for-the-badge&labelColor=a39774&color=ccac4b" alt="Downloads"></a>
  <a href="https://discord.gg/qKpPhkwGCY"><img src="https://img.shields.io/discord/1081609215484887051?style=for-the-badge&label=Discord&logo=discord&logoColor=d9e0ee&labelColor=a39774&color=ccac4b" alt="Discord"></a>
  <img src="https://img.shields.io/codefactor/grade/github/BMProjects-Development/HollowEngine?style=for-the-badge&labelColor=a39774&color=ccac4b" alt="CodeFactor">
</p>

---

## Introduction

HollowEngine is a content creation engine for Minecraft. It combines Kotlin scripting, a visual programming, and tools for building NPCs, events, gameplay systems, and interactive experiences.

## What It Provides

- In-game IDE with syntax highlighting and code analysis
- Kotlin Scripting with full access to the game's classes without having to re-enter the game
- A custom language for writing dialogues in the style of Ink / Yarn Spinner
- Advanced model system (.gltf, .obj, .fbx, .geo.json)
- Advanced UI framework that includes a Compose-like DSL, a CSS-like styling system, 3D transformations, masks, and effects
- NPC tooling with models, textures, animations, and interactions
- Scene and camera tooling for cinematic workflows
- Embedded asset, resource pack, and datapack loading

## Current Platform Support

- Minecraft `1.21.1`
- Fabric
- NeoForge
- Kotlin `2.4.0`

## Project Layout

- `runtime/`: main engine, UI, gameplay integration, tests
- `bridge/`: shared compatibility layer (bootstrap <---> bridge <---> runtime)
- `bootstrap/`: shared bootstrap code and resources
- `bootstrap-fabric/`: Fabric bootstrap implementation
- `bootstrap-neoforge/`: NeoForge bootstrap implementation
- `addons/compiler/`: scripting compiler addon

## Quick Start

1. Install the HollowEngine jar into your `mods/` folder. One jar covers both Fabric and NeoForge.
2. If you use scripting, install `HollowEngineCompiler-*.jar` as well. Addons work from `mods/` like any other mod, or from `hollowengine/addons/` if you prefer to keep them apart. If both folders contain the same addon id, the newest version is selected at startup.
3. Start Minecraft.
4. Open the in-game editor and begin creating content.

## Build From Source

Use the Gradle wrapper from the repository root:

- `.\gradlew.bat buildAndCollect` - build enabled bootstrap platforms and collect outputs into `merged/`
- `.\gradlew.bat test` - run JVM tests
- `.\gradlew.bat buildAddons` - build and collect every addon, including the compiler

## Links

- [Documentation](https://0mods.team/) - Guides and API reference
- [Discord Community](https://discord.gg/qKpPhkwGCY) - Help and updates
- [Telegram Channel](https://t.me/hollowengine) - Devlogs and announcements
- [AI Wiki Assistant](https://deepwiki.com/HollowHorizon/HollowEngine) - Interactive help

## License

This project is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License.

## Other

<div align = center>
<a href="https://www.star-history.com/?repos=HollowHorizon%2FHollowEngine&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=HollowHorizon/HollowEngine&type=date&theme=dark&legend=top-left&sealed_token=mg1yo5gPS_6Pv-pnckCZgik91hdi3ebpXVBFuykmMUHnJr_NRp5xsLA9DkXgCwxX5b2xdzhdGFyX97I_56WCBkSPLjA_ZO3g7OH3vnwdggSBVo81MdfGrg" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=HollowHorizon/HollowEngine&type=date&legend=top-left&sealed_token=mg1yo5gPS_6Pv-pnckCZgik91hdi3ebpXVBFuykmMUHnJr_NRp5xsLA9DkXgCwxX5b2xdzhdGFyX97I_56WCBkSPLjA_ZO3g7OH3vnwdggSBVo81MdfGrg" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=HollowHorizon/HollowEngine&type=date&legend=top-left&sealed_token=mg1yo5gPS_6Pv-pnckCZgik91hdi3ebpXVBFuykmMUHnJr_NRp5xsLA9DkXgCwxX5b2xdzhdGFyX97I_56WCBkSPLjA_ZO3g7OH3vnwdggSBVo81MdfGrg" />
 </picture>
</a>
</div>
