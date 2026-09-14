package ru.hollowhorizon.hollowengine.common.commands

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.system
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.particles.BedrockParticles
import ru.hollowhorizon.hollowengine.client.particles.ParticleEffect
import ru.hollowhorizon.hollowengine.client.particles.Transform
import ru.hollowhorizon.hollowengine.client.utils.mc
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.dialogue.DialogueInput
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.toReadablePath
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.*
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.Snapshot
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.snapshotOf
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.npcs.NpcAnimationRuntime
import ru.hollowhorizon.hollowengine.common.scripting.nodes.EntityNodeRuntime
import ru.hollowhorizon.hollowengine.common.scripting.nodes.addNode
import ru.hollowhorizon.hollowengine.common.scripting.nodes.removeNode
import ru.hollowhorizon.hollowengine.common.scripting.NODE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.RELOAD_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.STARTUP_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.UI_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.commands.arguments.ScriptPathArgument
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.state.StateContext
import ru.hollowhorizon.hollowengine.common.utils.*
import ru.hollowhorizon.hollowengine.client.models.bedrock.LivingEntityQuery
import java.io.File
import java.util.*
import kotlin.math.pow
import kotlin.math.roundToInt

@SubscribeEvent
fun onRegisterCommands(event: RegisterCommandsEvent) {
    event.dispatcher.onRegisterCommands {
        "hollowengine"("he") {
            registerParticleCommands()
            registerModelCommands()
            registerUtilityCommands()
            registerScriptingCommands()
            registerAddonCommands()
        }
    }
}

typealias CommandExtension = CommandEditor<CommandSourceStack, LiteralArgumentBuilder<CommandSourceStack>>

private fun CommandExtension.registerParticleCommands() {
    "particle"(
        arg("pos", Vec3Argument.vec3()),
        arg("name", StringArgumentType.greedyString()) { BedrockParticles.PARTICLES.keys.map { it.toString() } }
    ) {
        executes {
            spawnParticleAtPosition(
                StringArgumentType.getString(this, "name"),
                Vec3Argument.getVec3(this, "pos")
            )
            SUCCESS
        }
    }

    "particle"(
        arg("entity", EntityArgument.entity()),
        arg("name", StringArgumentType.greedyString()) { BedrockParticles.PARTICLES.keys.map { it.toString() } }
    ) {
        executes {
            spawnParticleOnEntity(
                StringArgumentType.getString(this, "name"),
                EntityArgument.getEntity(this, "entity") as LivingEntity
            )
            SUCCESS
        }
    }

    "remove-particles"(
        arg("name", StringArgumentType.greedyString()) { BedrockParticles.PARTICLES.keys.map { it.toString() } }
    ) {
        executes {
            removeParticles(StringArgumentType.getString(this, "name"))
            SUCCESS
        }
    }
}

private fun CommandExtension.registerModelCommands() {
    "model"(arg("model", StringArgumentType.string()) { getAvailableModels() }) {
        executes {
            val modelName = StringArgumentType.getString(this, "model")
            ShowModelInfoPacket(modelName).send(source.playerOrException)
            SUCCESS
        }
    }

    "model" {
        "info"(arg("model", StringArgumentType.string()) { getAvailableModels() }) {
            executes {
                val modelName = StringArgumentType.getString(this, "model")
                ShowModelInfoPacket(modelName).send(source.playerOrException)
                SUCCESS
            }
        }

        "attach"(
            arg("entity", EntityArgument.entity()),
            arg("model", StringArgumentType.string()) { getAvailableModels() }
        ) {
            executes {
                val host = EntityArgument.getEntity(this, "entity")
                val modelName = StringArgumentType.getString(this, "model")
                val snapshotId = attachNodeModel(host, modelName)
                sendSuccess { "Attached node model with snapshotId $snapshotId".literal }
            }
        }

        "player-preset"(arg("entity", EntityArgument.entity())) {
            executes {
                val host = EntityArgument.getEntity(this, "entity")
                applyStandardPlayerAnimationPreset(source, host, hideVanilla = true)
            }
        }

        "player-preset"(
            arg("entity", EntityArgument.entity()),
            arg("hideVanilla", BoolArgumentType.bool())
        ) {
            executes {
                val host = EntityArgument.getEntity(this, "entity")
                val hideVanilla = BoolArgumentType.getBool(this, "hideVanilla")
                applyStandardPlayerAnimationPreset(source, host, hideVanilla)
            }
        }

        "hide-vanilla"(
            arg("entity", EntityArgument.entity()),
            arg("hidden", BoolArgumentType.bool())
        ) {
            executes {
                val entity = EntityArgument.getEntity(this, "entity")
                setHideVanillaEntityModel(entity, BoolArgumentType.getBool(this, "hidden"))
                sendSuccess { "Vanilla model visibility component updated for ${entity.name.string}".literal }
            }
        }

        "animation" {
            "play"(
                arg("entity", EntityArgument.entity()),
                arg("animation", StringArgumentType.string())
            ) {
                executes {
                    val entity = EntityArgument.getEntity(this, "entity")
                    val animation = StringArgumentType.getString(this, "animation")
                    playEntityAnimation(source, entity, animation, AnimationPlayMode.Once)
                }
            }

            "play"(
                arg("entity", EntityArgument.entity()),
                arg("animation", StringArgumentType.string()),
                arg("mode", StringArgumentType.string()) { animationPlayModeNames() }
            ) {
                executes {
                    val entity = EntityArgument.getEntity(this, "entity")
                    val animation = StringArgumentType.getString(this, "animation")
                    val mode = parseAnimationPlayMode(StringArgumentType.getString(this, "mode"))
                    playEntityAnimation(source, entity, animation, mode)
                }
            }

            "play"(
                arg("entity", EntityArgument.entity()),
                arg("animation", StringArgumentType.string()),
                arg("mode", StringArgumentType.string()) { animationPlayModeNames() },
                arg("fadeIn", FloatArgumentType.floatArg(0f, 10f)),
                arg("fadeOut", FloatArgumentType.floatArg(0f, 10f))
            ) {
                executes {
                    val entity = EntityArgument.getEntity(this, "entity")
                    val animation = StringArgumentType.getString(this, "animation")
                    val mode = parseAnimationPlayMode(StringArgumentType.getString(this, "mode"))
                    val fadeIn = FloatArgumentType.getFloat(this, "fadeIn")
                    val fadeOut = FloatArgumentType.getFloat(this, "fadeOut")
                    playEntityAnimation(source, entity, animation, mode, fadeIn, fadeOut)
                }
            }

            "stop"(
                arg("entity", EntityArgument.entity()),
                arg("animation", StringArgumentType.string())
            ) {
                executes {
                    val entity = EntityArgument.getEntity(this, "entity")
                    val animation = StringArgumentType.getString(this, "animation")
                    stopEntityAnimation(source, entity, animation, DEFAULT_ANIMATION_FADE_DURATION)
                }
            }

            "stop"(
                arg("entity", EntityArgument.entity()),
                arg("animation", StringArgumentType.string()),
                arg("fade", FloatArgumentType.floatArg(0f, 10f))
            ) {
                executes {
                    val entity = EntityArgument.getEntity(this, "entity")
                    val animation = StringArgumentType.getString(this, "animation")
                    val fade = FloatArgumentType.getFloat(this, "fade")
                    stopEntityAnimation(source, entity, animation, fade)
                }
            }
        }

    }
}


private fun CommandExtension.registerUtilityCommands() {
    "hand" {
        executes {
            copyHandItemToClipboard(source.playerOrException)
            SUCCESS
        }
    }

    "pos" {
        executes {
            copyTargetPositionToClipboard(source.playerOrException)
            SUCCESS
        }
    }

    registerDialogueCommands()
}

private fun CommandExtension.registerDialogueCommands() {
    "dialogue" {
        "advance" {
            executes {
                if (DialogueInput.advance(source.playerOrException)) SUCCESS
                else sendFailure("hollowengine.commands.dialogue_not_in_dialogue".mcTranslate())
            }
        }

        "choose"(arg("option", IntegerArgumentType.integer(0))) {
            executes {
                val option = IntegerArgumentType.getInteger(this, "option")
                if (DialogueInput.choose(source.playerOrException, option)) SUCCESS
                else sendFailure("hollowengine.commands.dialogue_no_such_option".mcTranslate(option))
            }
        }
    }
}

private fun CommandExtension.registerScriptingCommands() {
    "scripting" {
        requires { hasPermission(2) }

        "run"(runnableScriptArgument()) {
            executes {
                val id = ScriptPathArgument.getScript(this, "path")
                if (isNodeScriptPath(id.path)) {
                    source.server.addNode(ScriptRegistry.display(id))
                    return@executes SUCCESS
                }
                runPlainScript(source, id)
            }
        }

        "run"(
            nodeScriptArgument(),
            arg("state", StringArgumentType.string())
        ) {
            executes {
                val id = ScriptPathArgument.getScript(this, "path")
                if (!isNodeScriptPath(id.path)) {
                    return@executes sendFailure(
                        "hollowengine.commands.scripting_state_requires_node_script"
                            .mcTranslate(ScriptRegistry.display(id))
                    )
                }
                source.server.addNode(
                    ScriptRegistry.display(id),
                    context = StateContext(nextState = StringArgumentType.getString(this, "state"))
                )
                SUCCESS
            }
        }

        "stop"(runningNodeArgument()) {
            executes {
                source.server.removeNode(ScriptRegistry.display(ScriptPathArgument.getScript(this, "path")))
                SUCCESS
            }
        }

        "attach"(
            arg("entity", EntityArgument.entity()),
            nodeScriptArgument(),
            arg("state", StringArgumentType.string())
        ) {
            executes {
                attachEntityNode(
                    EntityArgument.getEntity(this, "entity"),
                    ScriptPathArgument.getScript(this, "path"),
                    StringArgumentType.getString(this, "state"),
                )
            }
        }

        "attach"(
            arg("entity", EntityArgument.entity()),
            nodeScriptArgument()
        ) {
            executes {
                attachEntityNode(
                    EntityArgument.getEntity(this, "entity"),
                    ScriptPathArgument.getScript(this, "path"),
                    state = null,
                )
            }
        }

        "detach"(
            arg("entity", EntityArgument.entity()),
            attachedNodeArgument()
        ) {
            executes {
                val entity = EntityArgument.getEntity(this, "entity")
                val path = ScriptRegistry.display(ScriptPathArgument.getScript(this, "path"))
                if (EntityNodeRuntime.detach(entity, path)) SUCCESS
                else sendFailure("Node '$path' is not attached to ${entity.name.string}".literal)
            }
        }

        "list" {
            executes { listServerNodes(source) }

            "entity"(arg("entity", EntityArgument.entity())) {
                executes { listEntityNodes(source, EntityArgument.getEntity(this, "entity")) }
            }
        }

        "compile" {
            executes { compileAllScripts(source) }
        }
    }
}

/**
 * Fills the compilation cache for every known script, which is what turns a pack into something that
 * runs on a client without the Kotlin compiler addon installed.
 */
private fun compileAllScripts(source: CommandSourceStack): Int {
    val scripts = ScriptRegistry.list(".kts")
    if (scripts.isEmpty()) {
        source.sendFailure("No scripts were found".literal)
        return 0
    }
    val failures = ScriptLoader.compileAll(scripts)
    val compiled = scripts.size - failures.size
    failures.forEach { (id, error) ->
        HollowEngine.LOGGER.error("Failed to compile {}", ScriptRegistry.display(id), error)
        source.sendFailure("- ${ScriptRegistry.display(id)}: ${error.message}".literal)
    }
    source.sendSuccess({ "Compiled $compiled of ${scripts.size} scripts".literal }, true)
    return compiled
}

/** Every node script the engine can see, in the spelling the commands expect back. */
private fun nodeScriptArgument(): RequiredArgumentBuilder<CommandSourceStack, ScriptId> =
    arg<ScriptId, CommandSourceStack>("path", ScriptPathArgument.scriptPath()).suggests { _, builder ->
        SharedSuggestionProvider.suggest(DirectoryManager.componentScripts.map(ScriptRegistry::display), builder)
    }

/**
 * Everything `run` accepts: node scripts, plus plain `.kts` files, which is what one reaches for
 * when trying something out.
 */
private fun runnableScriptArgument(): RequiredArgumentBuilder<CommandSourceStack, ScriptId> =
    arg<ScriptId, CommandSourceStack>("path", ScriptPathArgument.scriptPath()).suggests { _, builder ->
        val nodes = DirectoryManager.componentScripts.map(ScriptRegistry::display)
        val plain = ScriptRegistry.list(".kts").filter { isPlainScriptPath(it.path) }.map(ScriptRegistry::display)
        SharedSuggestionProvider.suggest(nodes + plain, builder)
    }

/** Runs a plain script once, reporting whatever it threw straight back to the caller. */
private fun runPlainScript(source: CommandSourceStack, id: ScriptId): Int {
    val display = ScriptRegistry.display(id)
    if (!isPlainScriptPath(id.path)) {
        return source.sendFailure("'$display' is not a script /he scripting run can execute".literal).let { 0 }
    }
    return ScriptLoader.execute<Any?>(id).fold(
        onSuccess = {
            source.sendSuccess({ "Executed '$display'".literal }, true)
            1
        },
        onFailure = { error ->
            HollowEngine.LOGGER.error("Failed to run '{}'", display, error)
            source.sendFailure("Failed to run '$display': ${error.message ?: error::class.simpleName}".literal)
            0
        },
    )
}

/** Only the nodes actually running on this server, which is what one can stop. */
private fun runningNodeArgument(): RequiredArgumentBuilder<CommandSourceStack, ScriptId> =
    arg<ScriptId, CommandSourceStack>("path", ScriptPathArgument.scriptPath()).suggests { context, builder ->
        SharedSuggestionProvider.suggest(context.source.server.runtimeContext.nodes.paths(), builder)
    }

/** Only the nodes attached to the entity named earlier in the command. */
private fun attachedNodeArgument(): RequiredArgumentBuilder<CommandSourceStack, ScriptId> =
    arg<ScriptId, CommandSourceStack>("path", ScriptPathArgument.scriptPath()).suggests { context, builder ->
        val attached = runCatching { EntityNodeRuntime.paths(EntityArgument.getEntity(context, "entity")) }
            .getOrDefault(emptySet())
        SharedSuggestionProvider.suggest(attached, builder)
    }

private fun com.mojang.brigadier.context.CommandContext<CommandSourceStack>.attachEntityNode(
    entity: net.minecraft.world.entity.Entity,
    id: ScriptId,
    state: String?,
): Int {
    val path = ScriptRegistry.display(id)
    if (!isNodeScriptPath(id.path)) {
        return sendFailure("hollowengine.commands.scripting_state_requires_node_script".mcTranslate(path))
    }
    val context = state?.let { StateContext(nextState = it) }
    return if (EntityNodeRuntime.attach(entity, path, context = context)) {
        sendSuccess(true) { "Attached node '$path' to ${entity.name.string}".literal }
    } else {
        sendFailure("Failed to attach node '$path' to ${entity.name.string}".literal)
    }
}

private fun listServerNodes(source: CommandSourceStack): Int {
    val paths = source.server.runtimeContext.nodes.paths().sorted()
    if (paths.isEmpty()) {
        source.sendFailure("No server nodes are running".literal)
        return 0
    }
    source.sendSuccess({ "Server nodes:".literal }, false)
    paths.forEach { source.sendSuccess({ "- $it".literal }, false) }
    return paths.size
}

private fun listEntityNodes(source: CommandSourceStack, entity: net.minecraft.world.entity.Entity): Int {
    val paths = EntityNodeRuntime.paths(entity).sorted()
    if (paths.isEmpty()) {
        source.sendFailure("No nodes are attached to ${entity.name.string}".literal)
        return 0
    }
    source.sendSuccess({ "Nodes on ${entity.name.string}:".literal }, false)
    paths.forEach { source.sendSuccess({ "- $it".literal }, false) }
    return paths.size
}

internal fun isNodeScriptPath(path: String): Boolean = path.endsWith(".$NODE_SCRIPT_EXTENSION")

/** A `.kts` with no special role not a node, UI, reload or startup script, so running it just runs it. */
internal fun isPlainScriptPath(path: String): Boolean {
    if (!path.endsWith(".kts")) return false
    return listOf(NODE_SCRIPT_EXTENSION, UI_SCRIPT_EXTENSION, RELOAD_SCRIPT_EXTENSION, STARTUP_SCRIPT_EXTENSION)
        .none { path.endsWith(".$it") }
}

// region Particle Functions
private fun spawnParticleAtPosition(particleName: String, pos: Vec3) {
    val particleFile = BedrockParticles.PARTICLES[particleName.rl] ?: error("Particle not found")
    Minecraft.getInstance().level?.system?.spawn(
        ParticleEffect.fromFile(particleFile),
        transform = Transform.create(Vec3f(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat()))
    ) ?: error("Client level is not available")
}

private fun spawnParticleOnEntity(particleName: String, entity: LivingEntity) {
    val particleFile = BedrockParticles.PARTICLES[particleName.rl] ?: error("Particle not found")
    Minecraft.getInstance().level?.system?.spawn(
        ParticleEffect.fromFile(particleFile),
        query = LivingEntityQuery(entity)
    ) ?: error("Client level is not available")
}

private fun removeParticles(particleName: String) {
    val file = BedrockParticles.PARTICLES[particleName.rl] ?: error("Particle not found")
    Minecraft.getInstance().level?.system?.remove(
        file.particleEffect.description.identifier
    ) ?: error("Client level is not available")
}
// endregion

// region Utility Functions
private fun copyHandItemToClipboard(player: Player) {
    val item = player.mainHandItem
    val location = "\"" + BuiltInRegistries.ITEM.getKey(item.item).toString() + "\""
    val count = item.count
    val nbt = item.components


    val itemCommand = when {
        nbt == null && count > 1 -> "item($location, $count)"
        nbt == null -> "item($location)"
        else -> "item($location, $count, \"${nbt.toString().replace("\"", "\\\"")}\")"
    }

    CopyTextPacket(itemCommand).send(player as ServerPlayer)
}

private fun copyTargetPositionToClipboard(player: Player) {
    val loc = player.pick(100.0, 0.0f, true).location
    val roundedX = loc.x.roundTo(2)
    val roundedY = loc.y.roundTo(2)
    val roundedZ = loc.z.roundTo(2)
    CopyTextPacket("pos($roundedX, $roundedY, $roundedZ)").send(player as ServerPlayer)
}

private fun getAvailableModels(): Collection<String> {
    val defaultModels = listOf(
        "hollowengine:models/entity/player_model.gltf",
        "hollowengine:models/entity/player_model_slim.gltf",
        "hc:models/entity/hilda_regular.glb"
    )

    val customModels = DirectoryManager.HOLLOW_ENGINE.resolve("assets").toFile()
        .walk()
        .filter { it.path.endsWith(".gltf") || it.path.endsWith(".glb") }
        .map { it.toReadablePath().substring(7).replace(File.separator, "/").replaceFirst("/", ":") }
        .toList()

    return defaultModels + customModels
}


private fun applyStandardPlayerAnimationPreset(
    source: CommandSourceStack,
    host: net.minecraft.world.entity.Entity,
    hideVanilla: Boolean,
): Int {
    val snapshotId = attachNodeModel(
        host = host,
        modelName = StandardPlayerAnimatorPreset.MODEL,
    )
    if (!setHideVanillaEntityModel(host, hideVanilla)) {
        source.sendFailure("Hide vanilla model component is not registered".literal)
        return 0
    }
    source.sendSuccess(
        { "Applied standard player animation preset to ${host.name.string} with snapshotId $snapshotId".literal },
        true,
    )
    return 1
}

private fun playEntityAnimation(
    source: CommandSourceStack,
    entity: net.minecraft.world.entity.Entity,
    animation: String,
    playMode: AnimationPlayMode,
    fadeIn: Float = DEFAULT_ANIMATION_FADE_DURATION,
    fadeOut: Float = DEFAULT_ANIMATION_FADE_DURATION,
): Int {
    if (animation.isBlank()) {
        source.sendFailure("Animation name must not be blank".literal)
        return 0
    }

    NpcAnimationRuntime.apply(
        entity = entity,
        from = null,
        to = animation,
        playMode = playMode,
        fadeIn = fadeIn,
        fadeOut = fadeOut,
    )
    source.sendSuccess(
        {
            "Playing animation '$animation' on ${entity.name.string} with mode $playMode, fadeIn=${
                fadeIn.format(2)
            }s, fadeOut=${fadeOut.format(2)}s".literal
        },
        true,
    )
    return 1
}

private fun stopEntityAnimation(
    source: CommandSourceStack,
    entity: net.minecraft.world.entity.Entity,
    animation: String,
    fadeDuration: Float,
): Int {
    if (animation.isBlank()) {
        source.sendFailure("Animation name must not be blank".literal)
        return 0
    }

    NpcAnimationRuntime.apply(
        entity = entity,
        from = animation,
        to = null,
        playMode = AnimationPlayMode.Once,
        duration = fadeDuration,
    )
    source.sendSuccess(
        { "Stopping animation '$animation' on ${entity.name.string} with ${fadeDuration.format(2)}s fade".literal },
        true,
    )
    return 1
}

private fun parseAnimationPlayMode(value: String): AnimationPlayMode =
    AnimationPlayMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AnimationPlayMode.Once

private fun animationPlayModeNames(): List<String> =
    AnimationPlayMode.entries.map { it.name.lowercase() }

private fun setHideVanillaEntityModel(entity: net.minecraft.world.entity.Entity, hidden: Boolean): Boolean {
    val componentId = ComponentDescriptorRegistry.idFor(HideVanillaEntityModelComponent::class) ?: return false
    val components = AttachmentRegistry.componentsById(entity)
    if (hidden) {
        components[componentId] = HideVanillaEntityModelComponent()
    } else {
        components.remove(componentId)
    }
    return true
}

private const val DEFAULT_ANIMATION_FADE_DURATION = 0.33f

private fun attachNodeModel(
    host: net.minecraft.world.entity.Entity,
    modelName: String,
    extraComponents: List<Any> = emptyList(),
): UUID {
    val snapshotId = host.uuid
    val service = NodeRuntimeState.service(host.level())
    val snapshot = EntitySnapshot(
        components = listOf(
            Model(modelName),
            TransformComponent(),
        ) + extraComponents
    ).withEntity(host)
    service.materialize(snapshot)

    return snapshotId
}


private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

private fun Double.roundTo(numFractionDigits: Int): Double {
    val factor = 10.0.pow(numFractionDigits.toDouble())
    return (this * factor).roundToInt() / factor
}
// endregion

// region Packets
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class CopyTextPacket(val text: String) : HollowPacket {
    override fun handle(player: Player) {
        player.sendSystemMessage(
            "hollowengine.commands.copy".mcTranslate(text.literal)
                .onHoverText("hollowengine.tooltips.copy".mcTranslate)
                .onClickCopy(text)
        )
        mc.keyboardHandler.clipboard = text
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class ShowModelInfoPacket(val model: String) : HollowPacket {
    override fun handle(player: Player) {
        val location = model.rl
        Minecraft.getInstance().coroutineScope.launch {
            val hollowModel = HollowModelManager.getOrCreate(location)
                .filter { it !== Model.EMPTY }
                .first()

            player.sendSystemMessage(
                "hollowengine.commands.model_animations".mcTranslate(model.substringAfterLast('/'))
            )

            hollowModel.animationsByName.keys.forEach { anim ->
                player.sendSystemMessage(
                    ("- ".literal + anim.literal)
                        .onHoverText("hollowengine.tooltips.copy".mcTranslate)
                        .onClickCopy(anim)
                )
            }

            player.sendSystemMessage(
                "hollowengine.commands.model_textures".mcTranslate(model.substringAfterLast('/'))
            )

            hollowModel.materials.map { it.texture.path.removeSuffix(".png") }.forEach { texture ->
                player.sendSystemMessage(
                    ("- ".literal + texture.literal)
                        .onHoverText("hollowengine.tooltips.copy".mcTranslate)
                        .onClickCopy(texture)
                )
            }
        }
    }
}
// endregion






