# Аддон HollowEngine внутри мода

Шаблон аддона, который живёт в jar обычного мода — например, интеграция мода с движком. HE находит внутри мода `META-INF/hollowengine/mod-addon.properties` и запускает entrypoint, только если этот мод установлен.

Скопируйте эту папку в репозиторий своего мода (например, как `hollow-addon`). В `build.gradle.kts` **модуля мода** после блока `plugins` добавьте:

```kotlin
apply(from = rootProject.file("hollow-addon/addon.gradle.kts"))
```

Скрипт добавляет исходники и ресурсы шаблона в `main`, подключает dev JAR HE из `libs/` рядом с собой, coroutines и Koin через `compileOnly` и проверяет производственный JAR. Имя мода берётся из `modId` или `mod_id` в Gradle, либо из `fabric.mod.json` / `neoforge.mods.toml`. Запускайте обычную сборку мода (`assemble` или `build`): устанавливать нужно только полученный JAR мода.

Код аддона должен лежать в собственном пакете — в том, где находится entrypoint, и во вложенных в него. HE загружает этот пакет рядом с движком, потому что загрузчик модов классы движка не видит; остальные классы мода аддон получает как обычно, так что может свободно обращаться к ним. Обратное не работает: код мода не должен ссылаться на классы аддона.

Переименуйте пакет и `ExampleAddon`, затем обновите `entry` в `src/main/resources/META-INF/hollowengine/mod-addon.properties`. `id` — идентификатор аддона в HE, `hostModId` подставляется Gradle автоматически. Версия аддона совпадает с версией мода. В JAR мода должен сохраниться его обычный `fabric.mod.json` или `neoforge.mods.toml`.

Положите в `libs/` dev JAR HE (`HollowEngine-<minecraft>-<version>-dev.jar`): его собирает задача `developmentJar` движка, а `buildAndCollect` кладёт его в `merged/`. При смене версии HE замените этот файл. Для Fabric нужны official Mojang mappings либо Parchment поверх них; NeoForge использует official mappings. HE должен быть установлен в игре как зависимость мода.

Dev JAR содержит только классы HE, bridge и Kotlin-метаданные движка. Kotlin и сторонние библиотеки в него не включены. Kotlin подключает сам проект мода; его компилятор должен читать метаданные версии Kotlin, с которой собран HE. Шаблон отдельно подключает coroutines `1.11.0` и Koin `4.1.1`, типы которых входят в API аддонов. Их версии можно задать через `hollowEngineCoroutinesVersion` и `hollowEngineKoinVersion` в `gradle.properties`; используйте версии, совместимые с API установленного HE. Если аддон использует Compose UI, serialization или другие библиотечные API, добавьте соответствующие зависимости как `compileOnly` в сборке мода.

Такой аддон живёт вместе с модом и обновляется после перезапуска игры: `/he addons enable|disable|reload` к нему не применяются. Для независимого аддона, который устанавливают и обновляют отдельно, используется обычный формат 3 и отдельный JAR — см. [README аддонов](../README.md).
