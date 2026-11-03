package ru.hollowhorizon.hollowengine.addons.lighting.client

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import org.qualet.irl.patcher.PatchLibrary
import org.qualet.irl.patcher.Shaderpacks
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.coroutines.RuntimeDispatcherState
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterClientCommandsEvent

@ClientOnly
object LightingCommands {
    private lateinit var scope: CoroutineScope
    private val operations = Mutex()

    internal fun load(scope: CoroutineScope) {
        this.scope = scope
    }

    @SubscribeEvent
    fun register(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            literal("hollowengine").then(
                literal("lights").then(literal("packs").executes {
                message(
                    "packs", Shaderpacks.list().joinToString(", ")
                ); 1
            }).then(literal("patches").executes {
                message(
                    "patches", PatchLibrary.list().joinToString(", ") { it.fileName.toString() }); 1
            })
                .then(literal("reload").executes { runAsync(reloadResources = true) { LightingSettings.initialize(); "" }; 1 })
                .then(patchCommand("check", true)).then(patchCommand("patch", false))
            )
        )
    }

    private fun patchCommand(name: String, validate: Boolean) = literal(name).then(
        RequiredArgumentBuilder.argument<SharedSuggestionProvider, String>("pack", StringArgumentType.string())
            .suggests { _, builder ->
                SharedSuggestionProvider.suggest(
                    Shaderpacks.list().map { StringArgumentType.escapeIfRequired(it) }, builder
                )
            }
            .then(
                RequiredArgumentBuilder.argument<SharedSuggestionProvider, String>("patch", StringArgumentType.string())
                    .suggests { _, builder ->
                        SharedSuggestionProvider.suggest(
                            PatchLibrary.list().map { StringArgumentType.escapeIfRequired(it.fileName.toString()) },
                            builder
                        )
                    }.executes { context -> patch(context, validate); 1 }),
    )

    private fun patch(context: CommandContext<SharedSuggestionProvider>, validate: Boolean) {
        val pack = StringArgumentType.getString(context, "pack")
        val patch = StringArgumentType.getString(context, "patch")
        runAsync {
            val result = LightingShaderpacks.apply(pack, patch, validate)
            check(result.ok) { result.log.joinToString("\n") }
            result.summary
        }
    }

    private fun runAsync(reloadResources: Boolean = false, action: () -> String) {
        scope.launch(Dispatchers.IO) {
            val result = operations.withLock { runCatching(action) }
            withContext(RuntimeDispatcherState.clientDispatcher(Minecraft.getInstance())) {
                if (result.isSuccess && reloadResources) LightingClient.reloadResources()
                message(if (result.isSuccess) "success" else "error", result.getOrElse { it.message.orEmpty() })
            }
        }
    }

    private fun literal(name: String) = LiteralArgumentBuilder.literal<SharedSuggestionProvider>(name)
    private fun message(key: String, detail: String) {
        Minecraft.getInstance().player?.displayClientMessage(
            Component.literal("hollowengine.gui.lighting.$key".lang + " " + detail), false
        )
    }
}
