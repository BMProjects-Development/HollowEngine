package ru.hollowhorizon.hollowengine.addons.narrative.story

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate

/**
 * What the stories of an owner do, and starting, stopping and rewinding
 * them by hand, as a game master or an author testing a story does.
 */
internal object StoryCommands {
    private typealias Context = CommandContext<CommandSourceStack>

    fun register(root: CommandNode<CommandSourceStack>) {
        val story = Commands.literal("story").requires { it.hasPermission(2) }
            .then(actions(Commands.literal("server")) { StoryOwners.server(it.source.server) })
            .then(
                Commands.literal("entity").then(
                    actions(Commands.argument("entity", EntityArgument.entity())) {
                        StoryOwners.entity(EntityArgument.getEntity(it, "entity"))
                    },
                ),
            )
            .then(
                Commands.literal("node").then(
                    actions(Commands.argument("node", StringArgumentType.string()).suggests(nodes)) {
                        val path = StringArgumentType.getString(it, "node")
                        StoryOwners.serverNode(path) ?: throw StoryException("No node '$path' runs on the server")
                    },
                ),
            )
        root.addChild(story.build())
    }

    private fun <T : ArgumentBuilder<CommandSourceStack, T>> actions(builder: T, owner: (Context) -> StoryRuns): T {
        val keys = SuggestionProvider<CommandSourceStack> { context, suggestions ->
            SharedSuggestionProvider.suggest(runCatching { owner(context).keys }.getOrDefault(emptySet()).map(::quoted), suggestions)
        }
        val checkpoints = SuggestionProvider<CommandSourceStack> { context, suggestions ->
            val names = runCatching { owner(context)[key(context)]?.checkpoints?.map { it.first } }.getOrNull().orEmpty()
            SharedSuggestionProvider.suggest(names.distinct().map(::quoted), suggestions)
        }
        fun key() = Commands.argument("key", StringArgumentType.string()).suggests(keys)
        return builder
            .then(Commands.literal("list").executes { run(it) { list(it, owner(it)) } })
            .then(
                Commands.literal("start").then(
                    Commands.argument("story", StringArgumentType.string()).suggests(stories).then(
                        Commands.argument("key", StringArgumentType.string())
                            .executes { run(it) { start(it, owner(it), null) } }
                            .then(
                                Commands.argument("stage", StringArgumentType.string()).suggests(stages)
                                    .executes { run(it) { start(it, owner(it), StringArgumentType.getString(it, "stage")) } },
                            ),
                    ),
                ),
            )
            .then(Commands.literal("stop").then(key().executes { run(it) { stop(it, owner(it)) } }))
            .then(
                Commands.literal("rewind").then(
                    key().executes { run(it) { rewind(it, owner(it), null) } }.then(
                        Commands.argument("checkpoint", StringArgumentType.string()).suggests(checkpoints)
                            .executes { run(it) { rewind(it, owner(it), StringArgumentType.getString(it, "checkpoint")) } },
                    ),
                ),
            )
            .then(Commands.literal("checkpoints").then(key().executes { run(it) { checkpoints(it, owner(it)) } }))
    }

    private fun list(context: Context, runs: StoryRuns): Int {
        if (runs.keys.isEmpty()) {
            context.source.sendSuccess({ "hollowengine.commands.story.none".mcTranslate }, false)
            return 0
        }
        runs.keys.forEach { key ->
            val state = runs[key]?.state ?: return@forEach
            context.source.sendSuccess({ "hollowengine.commands.story.entry".mcTranslate(key, state) }, false)
        }
        return runs.keys.size
    }

    /**
     * Starts a `.story` as a scene, played to the player the stories belong to or else to whoever runs the command,
     * or a stage of a running script: [stage], or the only one it declares.
     */
    private fun start(context: Context, runs: StoryRuns, stage: String?): Int {
        val key = key(context)
        val story = StringArgumentType.getString(context, "story")
        if (story.endsWith(STORY_EXTENSION)) {
            if (stage != null) throw StoryException("A .story has no stages; it starts from its beginning")
            val player = runs.owner.entity as? ServerPlayer ?: context.source.player
                ?: throw StoryException("A scene is played to a player: start it for one or as one")
            runs.launchScene(key, story, listOf(player))
            context.source.sendSuccess({ "hollowengine.commands.story.started_scene".mcTranslate(key, story) }, true)
            return 1
        }
        val stages = StoryInstances.stagesOf(story)
        val chosen = stage ?: stages.singleOrNull()
            ?: throw StoryException("Script '$story' has the stages ${stages.sorted()}; name one after the key")
        runs.launch(key, story, chosen)
        context.source.sendSuccess({ "hollowengine.commands.story.started".mcTranslate(key, chosen) }, true)
        return 1
    }

    private fun stop(context: Context, runs: StoryRuns): Int {
        val key = key(context)
        if (!runs.stop(key)) return missing(context, key)
        context.source.sendSuccess({ "hollowengine.commands.story.stopped".mcTranslate(key) }, true)
        return 1
    }

    private fun rewind(context: Context, runs: StoryRuns, checkpoint: String?): Int {
        val key = key(context)
        val story = runs[key] ?: return missing(context, key)
        story.rewind(checkpoint)
        val message = if (checkpoint == null) "hollowengine.commands.story.rewound_latest".mcTranslate(key)
        else "hollowengine.commands.story.rewound".mcTranslate(key, checkpoint)
        context.source.sendSuccess({ message }, true)
        return 1
    }

    private fun checkpoints(context: Context, runs: StoryRuns): Int {
        val key = key(context)
        val story = runs[key] ?: return missing(context, key)
        story.checkpoints.forEachIndexed { index, (name, run) ->
            context.source.sendSuccess({ "hollowengine.commands.story.checkpoint".mcTranslate(index + 1, name, run) }, false)
        }
        return story.checkpoints.size
    }

    private fun missing(context: Context, key: String): Int {
        context.source.sendFailure("hollowengine.commands.story.missing".mcTranslate(key))
        return 0
    }

    /** Runs [command], telling the source why a story refused instead of failing the command with a stack trace. */
    private fun run(context: Context, command: () -> Int): Int = try {
        command()
    } catch (failure: StoryException) {
        context.source.sendFailure("hollowengine.commands.story.failed".mcTranslate(failure.message.orEmpty()))
        0
    }

    private fun key(context: Context): String = StringArgumentType.getString(context, "key")

    private fun quoted(value: String): String = StringArgumentType.escapeIfRequired(value)

    private const val STORY_EXTENSION = ".story"

    /** The suffix of a translation of a story, `intro.ru_ru.story`, which is played in place of it and not alone. */
    private val TRANSLATION = Regex("""\.[a-z]{2}_[a-z]{2}\.story$""")

    /** The running scripts and the `.story` files. */
    private val stories = SuggestionProvider<CommandSourceStack> { _, suggestions ->
        val files = ScriptRegistry.list(STORY_EXTENSION).map(ScriptRegistry::display).filterNot(TRANSLATION::containsMatchIn)
        SharedSuggestionProvider.suggest((StoryInstances.registered + files).map(::quoted), suggestions)
    }

    private val stages = SuggestionProvider<CommandSourceStack> { context, suggestions ->
        val script = runCatching { StringArgumentType.getString(context, "story") }.getOrNull()
        SharedSuggestionProvider.suggest(script?.let(StoryInstances::stagesOf).orEmpty().map(::quoted), suggestions)
    }

    private val nodes = SuggestionProvider<CommandSourceStack> { _, suggestions ->
        SharedSuggestionProvider.suggest(StoryOwners.serverNodes.map(::quoted), suggestions)
    }
}
