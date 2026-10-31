package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import java.util.UUID

/**
 * A story as authors edit it: nodes and the links between them. Both the list and the graph editor show this one
 * model, and [StoryGraphCompiler] turns it into a script.
 *
 * @param imports what the Kotlin of its nodes needs imported.
 * @param layout where the graph editor shows each node; a node without a place is arranged when it is shown.
 * @param groups the frames the graph editor keeps nodes in.
 * @param collapsed the nodes the graph editor shows as a title bar.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StoryGraph(
    @EncodeDefault val version: Int = VERSION,
    val imports: List<String> = emptyList(),
    @Serializable(SortedVariablesSerializer::class) val variables: List<StoryVariable> = emptyList(),
    @Serializable(SortedNodesSerializer::class) val nodes: List<StoryNode> = emptyList(),
    @Serializable(SortedLinksSerializer::class) val links: List<StoryLink> = emptyList(),
    val layout: Map<String, StoryPoint> = emptyMap(),
    val groups: List<StoryNodeGroup> = emptyList(),
    val collapsed: Set<String> = emptySet(),
) {
    fun node(id: String): StoryNode? = nodes.firstOrNull { it.id == id }

    /** The graph with every reroute joined back up: what the links mean, the way a story and the list read them. */
    fun dissolved(): StoryGraph {
        val reroutes = nodes.filterIsInstance<StoryNode.Reroute>().mapTo(HashSet()) { it.id }
        if (reroutes.isEmpty()) return this

        fun flowTarget(id: String): String? {
            val seen = HashSet<String>()
            var at = id
            while (at in reroutes) {
                if (!seen.add(at)) return null
                val from = at
                at = links.firstOrNull { it.from == from && it.isFlow }?.to ?: return null
            }
            return at
        }

        fun valueSource(link: StoryLink): StoryLink? {
            val seen = HashSet<String>()
            var at = link
            while (at.from in reroutes) {
                if (!seen.add(at.from)) return null
                val into = at.from
                at = links.firstOrNull { it.to == into && !it.isFlow } ?: return null
            }
            return at
        }
        val joined = links.mapNotNull { link ->
            when {
                link.isFlow -> if (link.from in reroutes) null else flowTarget(link.to)?.let { link.copy(to = it) }
                link.to in reroutes -> null
                else -> valueSource(link)?.let { link.copy(from = it.from, fromPin = it.fromPin) }
            }
        }
        return copy(nodes = nodes.filter { it.id !in reroutes }, links = joined)
    }

    /** The text of the file: the same content is always the same text, so a change is a small diff. */
    fun encode(): String = FORMAT.encodeToString(serializer(), copy(layout = layout.toSortedMap(), collapsed = collapsed.toSortedSet()))

    companion object {
        const val VERSION = 1

        private val FORMAT = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        fun decode(text: String): StoryGraph = FORMAT.decodeFromString(serializer(), text)

        /** An id for a new node. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)

        private const val ID_LENGTH = 10
    }
}

@Serializable
data class StoryPoint(val x: Float, val y: Float)

/** A frame of the graph editor around [nodes], or one node when [collapsed]; it means nothing to the story. */
@Serializable
data class StoryNodeGroup(
    val id: String,
    val title: String,
    val color: String = "gray",
    val nodes: List<String> = emptyList(),
    val collapsed: Boolean = false,
)

/** A variable of the story, saved with it. [initial] is a Kotlin expression; [type] is inferred from it when null. */
@Serializable
data class StoryVariable(val name: String, val initial: String, val type: String? = null)

/**
 * A connection from a pin of one node to a pin of another. Flow enters a node at [StoryPins.IN]; any other target
 * pin takes a value in place of the argument of that name.
 */
@Serializable
data class StoryLink(val from: String, val fromPin: String, val to: String, val toPin: String) {
    val isFlow: Boolean get() = toPin == StoryPins.IN
}

object StoryPins {
    const val IN = "in"
    const val NEXT = "next"
    const val THEN = "then"
    const val ELSE = "else"
    const val BODY = "body"
    const val VALUE = "value"
    const val SPEAKER = "speaker"
    const val TEXT = "text"
    const val CONDITION = "condition"
    const val ITEMS = "items"
    const val ITEM = "item"

    /** The argument an extension function is called on, unless the node names another. */
    const val RECEIVER = "this"

    fun option(index: Int): String = "option$index"

    fun optionIndex(pin: String): Int? = pin.removePrefix("option").takeIf { pin.startsWith("option") }?.toIntOrNull()
}

/** One answer of [StoryNode.Choose]. [condition], a Kotlin expression, offers it only when it holds. */
@Serializable
data class StoryOption(val text: String, val condition: String? = null)

/** An argument built by [constructor] from its own [arguments], such as `MoveOptions(speed = 1.2f)`. */
@Serializable
data class StoryGroup(val constructor: String, val arguments: Map<String, String> = emptyMap())

/** A step of a story. The [id] stays with the node for good: saves address what was generated from it. */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface StoryNode {
    val id: String

    /** Where a story can be started and flow can jump to; becomes the stage [name]. */
    @Serializable
    @SerialName("start")
    data class Start(override val id: String, val name: String) : StoryNode

    /**
     * A line. [speaker] is a name, `{expression}` for what an expression gives, or empty for narration. In [text],
     * `{name}` puts in the variable of that name, and `{player}` the name of the player. A link into
     * [StoryPins.TEXT] takes the place of [text].
     */
    @Serializable
    @SerialName("say")
    data class Say(override val id: String, val speaker: String = "", val text: String = "") : StoryNode

    /**
     * A call of [function], fully qualified or known to scripts. [arguments] are Kotlin expressions by parameter name.
     * With no flow into it the call is a value, placed where it is read.
     *
     * @param action the id of the action it calls, which the editors find its parameters by.
     * @param groups the arguments built from fields of their own.
     * @param receiver the argument it is called on, when it is an extension.
     * @param returns the type of a result that is read later.
     * @param block whether it takes the flow from [StoryPins.BODY] as its last argument, such as `temporary { }`.
     */
    @Serializable
    @SerialName("call")
    data class Call(
        override val id: String,
        val function: String,
        val action: String? = null,
        val arguments: Map<String, String> = emptyMap(),
        val groups: Map<String, StoryGroup> = emptyMap(),
        val receiver: String? = null,
        val returns: String? = null,
        val block: Boolean = false,
    ) : StoryNode

    /** Asks to choose one of [options]; each continues from its [StoryPins.option] pin. */
    @Serializable
    @SerialName("choose")
    data class Choose(override val id: String, val options: List<StoryOption>) : StoryNode

    /** Continues from [StoryPins.THEN] when [condition] holds and from [StoryPins.ELSE] otherwise. */
    @Serializable
    @SerialName("branch")
    data class Branch(override val id: String, val condition: String = "") : StoryNode

    /** Sets the variable [variable] to [value]. */
    @Serializable
    @SerialName("set")
    data class Assign(override val id: String, val variable: String, val value: String = "") : StoryNode

    /**
     * Kotlin that the graph does not look into. [inputs] are locals it can read, by name; [outputs] are the type of
     * each local it assigns for other nodes to read.
     */
    @Serializable
    @SerialName("code")
    data class Code(
        override val id: String,
        val code: String,
        val inputs: Map<String, String> = emptyMap(),
        val outputs: Map<String, String> = emptyMap(),
    ) : StoryNode

    /** A Kotlin expression other nodes read. */
    @Serializable
    @SerialName("expression")
    data class Expression(override val id: String, val code: String) : StoryNode

    /** A value worked out by the [StoryOperator] named [operator] from [arguments], Kotlin expressions by input name. */
    @Serializable
    @SerialName("pure")
    data class Pure(
        override val id: String,
        val operator: String,
        val arguments: Map<String, String> = emptyMap(),
    ) : StoryNode

    /** Runs the flow from [StoryPins.BODY] for each of [items], a Kotlin expression, giving it [StoryPins.ITEM]. */
    @Serializable
    @SerialName("for-each")
    data class Loop(override val id: String, val items: String = "") : StoryNode

    /** A point of the graph editor a link passes through, which the story does not notice. */
    @Serializable
    @SerialName("reroute")
    data class Reroute(override val id: String, val flow: Boolean) : StoryNode {
        val input: String get() = if (flow) StoryPins.IN else StoryPins.VALUE
        val output: String get() = if (flow) StoryPins.NEXT else StoryPins.VALUE
    }
}
