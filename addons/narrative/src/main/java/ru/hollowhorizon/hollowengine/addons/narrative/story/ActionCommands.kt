package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParamGroup
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueCharacter
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidget
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration

/** The actions as the commands of a story: `move-to` of whichever namespace has one, or `ns:move-to` of that one. */
object ActionCommands {
    private class Built(val actions: List<StoryAction>, val commands: List<StoryCommand>)

    @Volatile
    private var built = Built(emptyList(), emptyList())

    /** Every action a story can call: one whose required parameters all take values a field can hold. */
    val commands: List<StoryCommand>
        get() {
            val actions = StoryActions.all
            built.takeIf { it.actions === actions }?.let { return it.commands }
            return Built(actions, actions.mapNotNull(StoryCommand::of)).also { built = it }.commands
        }

    /** The commands [name] may mean: one, unless several namespaces declare it. */
    fun find(name: String): List<StoryCommand> = commands.filter { it.answersTo(name) }
}

/** An action as a story calls it. */
class StoryCommand private constructor(val action: StoryAction, val signature: StoryFunctionSignature) {
    val id: String get() = action.id

    internal fun answersTo(written: String): Boolean = if (':' in written) id == written else id.substringAfter(':') == written

    internal companion object {
        /** The command [action] is, or null when a story cannot call it: one of a script is not reachable by name. */
        fun of(action: StoryAction): StoryCommand? {
            if (!Modifier.isStatic(action.method.modifiers)) return null
            val direct = ArrayList<StoryParam>()
            val options = ArrayList<StoryParam>()
            for (parameter in action.function.parameters) {
                when (parameter.kind) {
                    KParameter.Kind.VALUE, KParameter.Kind.EXTENSION_RECEIVER -> Unit
                    else -> return null
                }
                if (parameter.type.classifier == CheckpointScope::class) return null
                if (!isValue(parameter.type)) {
                    val fields = optionsOf(parameter)
                    if (fields != null) options += fields else if (!parameter.isOptional) return null
                    continue
                }
                direct += param(parameter)
            }
            // A field of an options object named like a parameter of the action is left to the parameter.
            val taken = direct.mapTo(HashSet()) { it.name }
            val function = action.function
            val signature = StoryFunctionSignature(
                action.id,
                "${action.method.declaringClass.packageName}.${function.name}",
                direct + options.filter { taken.add(it.name) },
                title = function.findAnnotation<EditorName>()?.name,
                description = function.findAnnotation<EditorDescription>()?.description,
                icon = function.findAnnotation<EditorIcon>()?.icon,
                category = function.findAnnotation<EditorCategory>()?.category,
                categoryColor = function.findAnnotation<EditorCategory>()?.color?.ifEmpty { null },
                returns = function.returnType.takeIf { it.classifier is KClass<*> && it.classifier != Unit::class }
                    ?.toString()?.replace("!", ""),
                hidden = function.hasAnnotation<EditorHidden>(),
            )
            return StoryCommand(action, signature)
        }

        private fun param(parameter: KParameter): StoryParam {
            val info = parameter.findAnnotation<Param>()
            val receiver = parameter.kind == KParameter.Kind.EXTENSION_RECEIVER
            val implicit = parameter.type.classifier == ServerLevel::class && !parameter.isOptional
            return StoryParam(
                name = if (receiver) receiverName(parameter) else parameter.name.orEmpty(),
                type = parameter.type,
                optional = parameter.isOptional || implicit,
                suggestions = parameter.findAnnotation<Suggestions>()?.source?.objectInstance?.values
                    ?: suggestions(parameter.type),
                title = info?.title?.ifEmpty { null },
                description = info?.description?.ifEmpty { null },
                receiver = receiver,
                implicit = if (implicit) "storyLevel()" else null,
                assets = parameter.findAnnotation<EditorAsset>()?.extensions?.toList().orEmpty(),
                widget = parameter.findAnnotation<EditorWidget>()?.id,
            )
        }

        private fun receiverName(parameter: KParameter): String {
            val classifier = parameter.type.classifier as KClass<*>
            return when {
                classifier == ServerLevel::class -> "level"
                classifier.isSubclassOf(Entity::class) -> "who"
                else -> "this"
            }
        }

        /**
         * An optional parameter whose class can be built from optional constructor parameters alone, such as
         * `MoveOptions`: a story sets those of them it has values for, by name.
         */
        private fun optionsOf(parameter: KParameter): List<StoryParam>? {
            if (!parameter.isOptional) return null
            val type = parameter.type.classifier as? KClass<*> ?: return null
            val constructor = type.primaryConstructor ?: return null
            if (constructor.parameters.any { !it.isOptional }) return null
            val group = StoryParamGroup(parameter.name ?: return null, type.qualifiedName ?: return null)
            return constructor.parameters.filter { it.name != null && isValue(it.type) }
                .map { param(it).copy(optional = true, group = group) }.ifEmpty { null }
        }

        /** Whether a field can hold a value of [type]. */
        private fun isValue(type: KType): Boolean {
            val classifier = type.classifier as? KClass<*> ?: return false
            return when {
                classifier in VALUES || classifier.java.isEnum -> true
                classifier.isSubclassOf(Entity::class) || classifier.isSubclassOf(DialogueCharacter::class) -> true
                classifier in COLLECTIONS -> type.arguments.singleOrNull()?.type?.let(::isValue) == true
                else -> false
            }
        }

        /** The values the editor offers for [type]: the constants of an enum, of a list of them too. */
        private fun suggestions(type: KType): List<String> {
            val classifier = type.classifier as? KClass<*> ?: return emptyList()
            if (classifier in COLLECTIONS) return type.arguments.singleOrNull()?.type?.let(::suggestions).orEmpty()
            if (classifier == ServerLevel::class) return DIMENSIONS
            return classifier.java.enumConstants?.map { (it as Enum<*>).name.lowercase().replace('_', '-') }.orEmpty()
        }

        private val VALUES = setOf(
            String::class, Boolean::class, Int::class, Long::class, Short::class, Byte::class, Float::class, Double::class,
            Duration::class, ResourceLocation::class, ServerLevel::class, Vec3::class, BlockPos::class,
        )
        private val COLLECTIONS = setOf(List::class, Collection::class, Iterable::class, Set::class)
        private val DIMENSIONS = listOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")
    }
}
