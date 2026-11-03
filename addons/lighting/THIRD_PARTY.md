# IRLights

The `src/irl/java/org/qualet/irl` sources are vendored from
[irl-core](https://github.com/quaIett/irl-core), branch `1.21.1`, commit
`361d887bc05a8712ef002495aabc524cf840a81c` (version 1.1.7), under the MIT license.
Minecraft references are ported from Yarn to Mojang mappings. Keeping the upstream
GPU pipeline together preserves the `.irlights` ABI, shadow atlas layout and patch
compatibility. Upstream classes retain their structure, including their size, to
make auditing and subsequent upstream updates possible.

Bundled `.irlights` patches come from [irl-editor](https://github.com/quaIett/irl-editor),
branch `port/1.21.1`, commit `d1f41b4c617119d9655a18e958c051ddd87f9476`.
Shaderpacks themselves are not included. Their original licenses still apply.

Both MIT license texts are included in `src/main/resources/META-INF/licenses/`.

Local changes beyond Mojang mappings: imports replace fully qualified expressions;
patch application refuses existing/contained destinations and escaping patch paths;
`HollowShadowCasters` supplies the engine's entity geometry. GPU binary layouts,
sampler names, profile magic, shadow atlas allocation and bundled patches are retained.
Sampler registration is synchronized for addon loading concurrent with shader compilation.
