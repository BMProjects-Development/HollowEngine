package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.components.ComponentCommands
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.LIST_OF
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.MAP_OF
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.generic
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.input
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.number
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.operator
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.out
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.outOf
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl.typed
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.addons.narrative.story.ValueOperators

/**
 * An input of a [StoryOperator]. [default] is the Kotlin it reads with nothing typed or linked.
 *
 * @param param the parameter of the function of a [StoryOperator.function] it passes, which says how its field is
 * written, what it offers and what it is called.
 * @param path where it sits among the sections of the block, `jumps.maxGap` under `jumps`.
 * @param shape the exact type it takes, written with the type parameters of the block, as `List<T>` or `T?`; what is
 * given to such inputs and taken from such outputs tells what those parameters are.
 * @param typeName whether it names a Kotlin type rather than takes a value: it has no pin, and its field goes into
 * the template as it is written, as in `$value as? $type`.
 */
data class StoryOperatorInput(
    val name: String,
    val type: StoryPinType,
    val default: String = "",
    val param: StoryParam? = null,
    val path: String = name,
    val shape: String? = null,
    val typeName: Boolean = false,
) {
    val generic: Boolean get() = shape?.let(StoryOperatorDsl::parametersOf)?.isNotEmpty() == true

    /** What is typed into the field of this input as Kotlin. */
    fun expression(text: String): String =
        param?.let { StoryLiterals.expression(text, it.type) } ?: type.expression(text)

    /** How the field of this input shows the Kotlin [expression]. */
    fun text(expression: String): String =
        param?.let { StoryLiterals.text(expression, it.type) } ?: type.text(expression)
}

/**
 * An output of a [StoryOperator]. In [template], `$name` stands for what the input of that name reads.
 *
 * @param kotlin its exact type, until analysis tells it for the inputs at hand.
 * @param empty what it reads with none of the inputs of a variadic operator given.
 * @param path where it sits among the sections of the block.
 * @param shape the type it gives, written with the type parameters of the block, as `List<T>`.
 * @param typeOf the input naming the type it gives, which may be missing.
 * @param title what the editor calls it instead of [name], a lang key or plain text.
 */
data class StoryOperatorOutput(
    val name: String,
    val type: StoryPinType,
    val template: String,
    val kotlin: String? = null,
    val empty: String? = null,
    val path: String = name,
    val shape: String? = null,
    val typeOf: String? = null,
    val title: String? = null,
) {
    val generic: Boolean get() = shape?.let(StoryOperatorDsl::parametersOf)?.isNotEmpty() == true
}

/**
 * A value worked out on the spot: no flow goes through it, and every node reading it reads it anew.
 *
 * @param variadic whether the author can add inputs like the last of [inputs]; `$*` in a template stands for all of them.
 * @param function the qualified name of the function of a [StoryValue][ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue]
 * it calls instead of a template, with its inputs passed by name and those left empty out.
 * @param title what the editor calls it, a lang key or plain text, when its id does not say.
 * @param description what it reads, a lang key or plain text.
 * @param icon a `namespace:textures/...` icon of its own.
 */
data class StoryOperator(
    val id: String,
    val category: String,
    val inputs: List<StoryOperatorInput>,
    val outputs: List<StoryOperatorOutput>,
    val variadic: Boolean = false,
    val function: String? = null,
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
)

/** The operators graphs are built from. */
object StoryOperators {
    private val NUMBER = StoryPinType.NUMBER
    private val TEXT = StoryPinType.TEXT
    private val BOOL = StoryPinType.BOOL
    private val VECTOR = StoryPinType.VECTOR
    private val LIST = StoryPinType.LIST
    private val MAP = StoryPinType.MAP
    private val ANY = StoryPinType.ANY
    private val PLAYER = StoryPinType.PLAYER
    private val OBJECT = StoryPinType.OBJECT

    /** Two numbers into one, written over `$a` and `$b` as doubles. */
    private fun math(id: String, template: String, b: String = "0") = operator(
        id, "values.math", listOf(number("a"), number("b", b)),
        out("result", NUMBER, template.replace("A", "$" + "a.toDouble()").replace("B", "$" + "b.toDouble()")),
    )

    /** A number into another, written over `$a` as a double. */
    private fun rounded(id: String, template: String) = operator(
        id, "values.math", listOf(number("a")), out("result", NUMBER, template.replace("A", "$" + "a.toDouble()")),
    )

    private fun compare(id: String, sign: String) = operator(
        id,
        "values.logic",
        listOf(number("a"), number("b")),
        out("result", BOOL, $$"$a.toDouble() $$sign $b.toDouble()"),
    )

    private fun logic(id: String, sign: String) = operator(
        id,
        "values.logic",
        listOf(input("a", BOOL, "false"), input("b", BOOL, "false")),
        out("result", BOOL, $$"$a $$sign $b"),
    )

    private fun vectors(id: String, template: String) = operator(
        id,
        "values.vector",
        listOf(input("a", VECTOR, "Vec3.ZERO"), input("b", VECTOR, "Vec3.ZERO")),
        out("result", VECTOR, template),
    )

    private val builtins: List<StoryOperator> = listOf(
        math("add", "A + B"),
        math("subtract", "A - B"),
        math("multiply", "A * B", "1"),
        math("divide", "A / B", "1"),
        math("modulo", "A.mod(B)", "1"),
        math("power", "Math.pow(A, B)", "1"),
        math("min", "minOf(A, B)"),
        math("max", "maxOf(A, B)"),
        rounded("abs", "Math.abs(A)"),
        rounded("sqrt", "Math.sqrt(A)"),
        rounded("floor", "Math.floor(A)"),
        rounded("ceil", "Math.ceil(A)"),
        rounded("round", "Math.rint(A)"),
        rounded("sin", "Math.sin(A)"),
        rounded("cos", "Math.cos(A)"),
        operator(
            "clamp", "values.math", listOf(number("value"), number("min"), number("max", "1")),
            out("result", NUMBER, $$"$value.toDouble().coerceIn($min.toDouble(), $max.toDouble())"),
        ),
        operator(
            "lerp", "values.math", listOf(number("from"), number("to", "1"), number("t", "0.5")),
            out("result", NUMBER, $$"$from.toDouble() + ($to.toDouble() - $from.toDouble()) * $t.toDouble()"),
        ),
        operator(
            "random", "values.math", listOf(number("min"), number("max", "1")),
            out("result", NUMBER, $$"$min.toDouble() + Math.random() * ($max.toDouble() - $min.toDouble())"),
        ),

        compare("less", "<"),
        compare("less-equal", "<="),
        compare("greater", ">"),
        compare("greater-equal", ">="),
        operator(
            "story-context", "story", emptyList(),
            out("player", PLAYER, "storyPlayer()"),
            out("players", LIST, "storyPlayers()").copy(kotlin = "List<net.minecraft.server.level.ServerPlayer>"),
            out("level", OBJECT, "storyLevel()").copy(kotlin = "net.minecraft.server.level.ServerLevel"),
        ),
        operator(
            "has-value", "values.logic", listOf(generic("value", empty = true)), out("result", BOOL, "\$value != null"),
        ),
        operator(
            "or-default", "values.logic", listOf(generic("value", empty = true), generic("default")),
            outOf("result", $$"($value ?: $default)"),
        ),
        operator(
            "or-error",
            "values.logic",
            listOf(generic("value", empty = true), input("message", TEXT, "\"A value is missing\"")),
            outOf("result", $$"($value ?: throw StoryException($message))"),
        ),
        operator(
            "is-null", "values.logic", listOf(generic("value", empty = true)), out("result", BOOL, "\$value == null"),
        ),
        operator(
            "cast",
            "values.types",
            listOf(input("value", ANY), StoryOperatorInput("type", ANY, typeName = true)),
            out("result", ANY, $$"($value as? $type)").copy(kotlin = "kotlin.Any?", typeOf = "type"),
            out("is", BOOL, $$"($value is $type)"),
        ),
        operator(
            "equal", "values.logic", listOf(generic("a", "0"), generic("b", "0")), out("result", BOOL, $$"$a == $b")
        ),
        operator(
            "not-equal", "values.logic", listOf(generic("a", "0"), generic("b", "0")), out("result", BOOL, $$"$a != $b")
        ),
        logic("and", "&&"),
        logic("or", "||"),
        logic("xor", "!="),
        operator("not", "values.logic", listOf(input("a", BOOL, "false")), out("result", BOOL, $$"!$a")),
        operator(
            "select",
            "values.logic",
            listOf(input("condition", BOOL, "false"), generic("then"), generic("else")),
            outOf("result", $$"if ($condition) $then else $else"),
        ),

        operator(
            "vector", "values.vector", listOf(number("x"), number("y"), number("z")),
            out("vector", VECTOR, $$"vec($x, $y, $z)"),
        ),
        operator(
            "vector-split", "values.vector", listOf(input("vector", VECTOR, "Vec3.ZERO")),
            out("x", NUMBER, $$"$vector.x"), out("y", NUMBER, $$"$vector.y"), out("z", NUMBER, $$"$vector.z"),
        ),
        vectors("vector-add", $$"$a.add($b)"),
        vectors("vector-subtract", $$"$a.subtract($b)"),
        operator(
            "vector-scale", "values.vector", listOf(input("vector", VECTOR, "Vec3.ZERO"), number("factor", "1")),
            out("result", VECTOR, $$"$vector.scale($factor.toDouble())"),
        ),
        operator(
            "distance", "values.vector", listOf(input("a", VECTOR, "Vec3.ZERO"), input("b", VECTOR, "Vec3.ZERO")),
            out("result", NUMBER, $$"$a.distanceTo($b)"),
        ),
        operator(
            "vector-length", "values.vector", listOf(input("vector", VECTOR, "Vec3.ZERO")),
            out("length", NUMBER, $$"$vector.length()"), out("normalized", VECTOR, $$"$vector.normalize()"),
        ),
        operator(
            "vector-lerp", "values.vector",
            listOf(input("from", VECTOR, "Vec3.ZERO"), input("to", VECTOR, "Vec3.ZERO"), number("t", "0.5")),
            out("result", VECTOR, $$"$from.lerp($to, $t.toDouble())"),
        ),

        operator(
            "join-text",
            "values.text",
            listOf(input("a", ANY, "\"\""), input("b", ANY, "\"\"")),
            out("result", TEXT, $$"\"${$a}${$b}\"")
        ),
        operator(
            "format-text", "values.text",
            listOf(
                input("format", TEXT),
                input("a", ANY, "\"\""),
                input("b", ANY, "\"\""),
                input("c", ANY, "\"\""),
                input("d", ANY, "\"\"")
            ),
            out("result", TEXT, $$"$format.format($a, $b, $c, $d)"),
        ),
        operator(
            "to-text", "values.text", listOf(input("value", ANY, "\"\"")), out("result", TEXT, $$"$value.toString()")
        ),
        operator(
            "to-number",
            "values.text",
            listOf(input("text", TEXT, "\"0\"")),
            out("result", NUMBER, $$"($text.toDoubleOrNull() ?: 0.0)")
        ),
        operator(
            "text-length",
            "values.text",
            listOf(input("text", TEXT, "\"\"")),
            out("length", NUMBER, $$"$text.length.toDouble()")
        ),
        operator(
            "text-contains", "values.text", listOf(input("text", TEXT, "\"\""), input("part", TEXT, "\"\"")),
            out("result", BOOL, $$"$text.contains($part)"),
        ),
        operator(
            "text-replace",
            "values.text",
            listOf(input("text", TEXT, "\"\""), input("from", TEXT, "\"\""), input("to", TEXT, "\"\"")),
            out("result", TEXT, $$"$text.replace($from, $to)"),
        ),

        operator(
            "list", "values.list", listOf(generic("a"), generic("b"), generic("c")),
            outOf("list", "listOf(\$*)", LIST_OF, LIST).copy(empty = "emptyList<Nothing>()"),
        ).copy(variadic = true),
        operator(
            "range", "values.list", listOf(number("from"), number("to", "9")),
            out("list", LIST, $$"($from.toInt()..$to.toInt()).toList()").copy(kotlin = "List<Int>"),
        ),
        operator(
            "list-size", "values.list", listOf(input("list", LIST)), out("size", NUMBER, $$"$list.size.toDouble()")
        ),
        operator(
            "list-get",
            "values.list",
            listOf(typed("list", LIST_OF, LIST), number("index")),
            outOf("item", $$"$list[$index.toInt()]")
        ),
        operator(
            "list-contains",
            "values.list",
            listOf(typed("list", LIST_OF, LIST), generic("item")),
            out("result", BOOL, $$"$item in $list")
        ),
        operator(
            "list-add",
            "values.list",
            listOf(typed("list", LIST_OF, LIST), generic("item")),
            outOf("list", $$"$list + $item", LIST_OF, LIST)
        ),
        operator(
            "list-remove",
            "values.list",
            listOf(typed("list", LIST_OF, LIST), generic("item")),
            outOf("list", $$"$list - $item", LIST_OF, LIST)
        ),

        operator(
            "map-get",
            "values.list",
            listOf(typed("map", MAP_OF, MAP), typed("key", "K")),
            outOf("value", $$"$map[$key]", "V?")
        ),
        operator(
            "map-has",
            "values.list",
            listOf(typed("map", MAP_OF, MAP), typed("key", "K")),
            out("result", BOOL, $$"$key in $map")
        ),
        operator(
            "map-put",
            "values.list",
            listOf(typed("map", MAP_OF, MAP), typed("key", "K"), typed("value", "V")),
            outOf("map", $$"$map + ($key to $value)", MAP_OF, MAP)
        ),
        operator(
            "map-remove",
            "values.list",
            listOf(typed("map", MAP_OF, MAP), typed("key", "K")),
            outOf("map", $$"$map - $key", MAP_OF, MAP)
        ),
        operator(
            "map-keys",
            "values.list",
            listOf(typed("map", MAP_OF, MAP)),
            outOf("keys", $$"$map.keys.toList()", "kotlin.collections.List<K>", LIST),
            out("size", NUMBER, $$"$map.size.toDouble()"),
        ),
    )

    private val byId = builtins.associateBy { it.id }

    val all: List<StoryOperator> get() = builtins + ValueOperators.all + TypeOperators.all + ComponentCommands.readers

    fun find(id: String): StoryOperator? =
        byId[id] ?: TypeOperators.find(id) ?: ComponentCommands.reader(id) ?: ValueOperators.find(id)

    /** The name of input at [index] of a variadic operator. */
    fun inputName(index: Int): String = if (index < LETTERS) "${'a' + index}" else "item$index"

    /** The inputs of operator of [node], with those author added. */
    fun inputsOf(node: StoryNode.Pure): List<StoryOperatorInput> {
        val operator = find(node.operator) ?: return emptyList()
        val last = operator.inputs.lastOrNull()
        if (!operator.variadic || last == null) return operator.inputs
        return List(node.count ?: operator.inputs.size) { last.copy(name = inputName(it)) }
    }

    private const val LETTERS = 26
}
