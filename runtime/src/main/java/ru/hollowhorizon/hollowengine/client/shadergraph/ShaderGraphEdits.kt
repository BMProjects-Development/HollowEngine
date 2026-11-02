package ru.hollowhorizon.hollowengine.client.shadergraph

/** An id no node of the graph has yet, readable, since it shows up in the file. */
fun ShaderGraph.freeNodeId(type: String): String {
    val base = type.substringAfterLast('/').ifBlank { "node" }
    var index = 1
    while (nodes.any { it.id == "${base}_$index" }) index++
    return "${base}_$index"
}

fun ShaderGraph.withNode(node: ShaderGraphNode): ShaderGraph = copy(nodes = nodes + node)

/**
 * Without the nodes of [ids], every link into or out of them, and their places in groups. A reroute is
 * dissolved rather than cut: what fed it goes on feeding what it fed, so tidying a link up and taking
 * the tidying away again leaves the graph as it was.
 */
fun ShaderGraph.withoutNodes(ids: Set<String>): ShaderGraph {
    fun sourceOf(reroute: String): ShaderGraphLink? {
        val seen = hashSetOf(reroute)
        var link = linkInto(reroute, ShaderNodeLibrary.REROUTE_INPUT) ?: return null
        while (link.from in ids && node(link.from)?.type == ShaderNodeLibrary.REROUTE) {
            if (!seen.add(link.from)) return null
            link = linkInto(link.from, ShaderNodeLibrary.REROUTE_INPUT) ?: return null
        }
        return link.takeIf { it.from !in ids }
    }

    val bridged = links.filter { it.from in ids && it.to !in ids && node(it.from)?.type == ShaderNodeLibrary.REROUTE }
        .mapNotNull { out -> sourceOf(out.from)?.let { ShaderGraphLink(it.from, it.output, out.to, out.input) } }
    return copy(
        nodes = nodes.filterNot { it.id in ids },
        links = links.filterNot { it.from in ids || it.to in ids } + bridged,
        groups = groups.map { it.copy(nodes = it.nodes - ids) }.filter { it.nodes.isNotEmpty() },
    )
}

/**
 * A reroute [id] at ([x], [y]) put into [link]: what fed the link feeds the reroute, and the reroute
 * feeds where the link went.
 */
fun ShaderGraph.withReroute(link: ShaderGraphLink, id: String, x: Float, y: Float): ShaderGraph {
    if (link !in links) return this
    return withoutLink(link).withNode(ShaderGraphNode(id, ShaderNodeLibrary.REROUTE, x, y))
        .withLink(link.from, link.output, id, ShaderNodeLibrary.REROUTE_INPUT)
        .withLink(id, ShaderNodeLibrary.REROUTE_OUTPUT, link.to, link.input)
}

/** An id no group has yet. */
fun ShaderGraph.freeGroupId(): String {
    var index = 1
    while (groups.any { it.id == "group_$index" }) index++
    return "group_$index"
}

/**
 * The nodes of [ids] put together in a new group called [title], taken out of whatever group they
 * were in; a group left with nothing in it goes. Returns the id of the new group too.
 */
fun ShaderGraph.withGroup(ids: Set<String>, title: String): Pair<ShaderGraph, String> {
    val members = nodes.map { it.id }.filter { it in ids }
    if (members.isEmpty()) return this to ""
    val id = freeGroupId()
    return withMembership(ids, group = null).let { it.copy(groups = it.groups + ShaderGraphGroup(id, title, members)) } to id
}

/**
 * The nodes of [ids] moved into [group], or out of every group when it is null. A node is in one group
 * at most, so they leave the ones they were in, and a group left with nothing in it goes.
 */
fun ShaderGraph.withMembership(ids: Set<String>, group: String?): ShaderGraph {
    val joining = nodes.map { it.id }.filter { it in ids }
    return copy(groups = groups.mapNotNull { each ->
        val members = (each.nodes - ids) + if (each.id == group) joining else emptyList()
        each.copy(nodes = members).takeIf { members.isNotEmpty() }
    })
}

/** Without the group; its nodes stay where they are. */
fun ShaderGraph.withoutGroup(id: String): ShaderGraph = copy(groups = groups.filterNot { it.id == id })

fun ShaderGraph.withGroupChanged(id: String, change: (ShaderGraphGroup) -> ShaderGraphGroup): ShaderGraph =
    copy(groups = groups.map { if (it.id == id) change(it) else it })

fun ShaderGraph.withNodeAt(id: String, x: Float, y: Float): ShaderGraph =
    mapNode(id) { it.copy(x = x, y = y) }

/** With each node of [positions] moved to where it says. */
fun ShaderGraph.withNodesAt(positions: Map<String, Pair<Float, Float>>): ShaderGraph = copy(
    nodes = nodes.map { node -> positions[node.id]?.let { (x, y) -> node.copy(x = x, y = y) } ?: node },
)

/**
 * Copies of the nodes of [ids] next to them, with their values and options and the links between
 * them, but none of the links that reach outside. Returns the ids of the copies too.
 */
fun ShaderGraph.withDuplicates(ids: Set<String>, offset: Float = 30f): Pair<ShaderGraph, List<String>> {
    var graph = this
    val renamed = LinkedHashMap<String, String>()
    nodes.filter { it.id in ids }.forEach { node ->
        val copy = node.copy(id = graph.freeNodeId(node.type), x = node.x + offset, y = node.y + offset)
        renamed[node.id] = copy.id
        graph = graph.withNode(copy)
    }
    val copiedLinks = links.filter { it.from in renamed && it.to in renamed }
        .map { it.copy(from = renamed.getValue(it.from), to = renamed.getValue(it.to)) }
    return graph.copy(links = graph.links + copiedLinks) to renamed.values.toList()
}

/**
 * With a link from [output] of [from] into [input] of [to]. An input takes one link, so whatever was
 * linked into it before is dropped; a link that would close a loop is not made.
 */
fun ShaderGraph.withLink(from: String, output: String, to: String, input: String): ShaderGraph {
    if (from == to || dependsOn(from, to)) return this
    return copy(links = links.filterNot { it.to == to && it.input == input } + ShaderGraphLink(from, output, to, input))
}

fun ShaderGraph.withoutLinkInto(node: String, input: String): ShaderGraph =
    copy(links = links.filterNot { it.to == node && it.input == input })

fun ShaderGraph.withoutLink(link: ShaderGraphLink): ShaderGraph = copy(links = links - link)

/** With [values] for the unconnected input [input] of the node. */
fun ShaderGraph.withValue(node: String, input: String, values: List<Float>): ShaderGraph =
    mapNode(node) { it.copy(values = it.values + (input to values)) }

/**
 * With [option] of the node set to [value]. A node whose pins follow its options, such as a shape or
 * an expression, may lose a pin that way, and the links into a pin it lost go with it.
 */
fun ShaderGraph.withOption(node: String, option: String, value: String): ShaderGraph {
    val changed = mapNode(node) { it.copy(options = it.options + (option to value)) }
    val updated = changed.node(node) ?: return changed
    val kind = ShaderNodeTypes.of(updated.type) ?: return changed
    val pins = kind.inputs(updated).map { it.name }.toSet()
    return changed.copy(links = changed.links.filterNot { it.to == node && it.input !in pins })
}

fun ShaderGraph.withPreview(node: String, shown: Boolean): ShaderGraph = mapNode(node) { it.copy(preview = shown) }

fun ShaderGraph.withCollapsed(node: String, collapsed: Boolean): ShaderGraph =
    mapNode(node) { it.copy(collapsed = collapsed) }

fun ShaderGraph.withPreviewSettings(settings: ShaderGraphPreview): ShaderGraph = copy(preview = settings)

/**
 * The graph as one of [target]: its output node swapped for the one of the target, in the same place
 * and under the same id, keeping what is linked into and typed on the inputs both have. A graph left
 * as it was made becomes what a new graph of the target starts as.
 */
fun ShaderGraph.withTarget(target: ShaderTarget): ShaderGraph {
    if (target == this.target) return this
    if (copy(preview = ShaderGraphPreview()) == ShaderNodeLibrary.default(this.target)) {
        return ShaderNodeLibrary.default(target).copy(preview = preview)
    }
    val kind = ShaderNodeTypes.master(target) ?: return copy(target = target)
    val masters = nodes.filter { ShaderNodeTypes.of(it.type)?.master != null }.map { it.id }.toSet()
    if (masters.isEmpty()) return copy(target = target).withNode(ShaderGraphNode(freeNodeId(kind.id), kind.id))
    val pins = kind.inputs(kind.prototype).map { it.name }.toSet()
    return copy(
        target = target,
        nodes = nodes.map { node ->
            if (node.id in masters) node.copy(type = kind.id, values = node.values.filterKeys { it in pins }) else node
        },
        links = links.filterNot { it.to in masters && it.input !in pins },
    )
}

/** An id no property has yet: `Property`, `Property2` and so on. */
fun ShaderGraph.freePropertyName(base: String = "Property"): String {
    if (properties.none { it.name == base }) return base
    var index = 2
    while (properties.any { it.name == "$base$index" }) index++
    return "$base$index"
}

fun ShaderGraph.withProperty(property: ShaderGraphProperty): ShaderGraph =
    copy(properties = properties.filterNot { it.name == property.name } + property)

/** With the property named [name] replaced by [property], and its nodes following a rename. */
fun ShaderGraph.withPropertyChanged(name: String, property: ShaderGraphProperty): ShaderGraph {
    if (property.name != name && properties.any { it.name == property.name }) return this
    val renamed = nodes.map { node ->
        if (node.type == ShaderNodeLibrary.PROPERTY && node.options["property"] == name) {
            node.copy(options = node.options + ("property" to property.name))
        } else {
            node
        }
    }
    return copy(properties = properties.map { if (it.name == name) property else it }, nodes = renamed)
}

fun ShaderGraph.withoutProperty(name: String): ShaderGraph = copy(properties = properties.filterNot { it.name == name })

/** Whether [node] reads, however far back, from [other]. */
fun ShaderGraph.dependsOn(node: String, other: String): Boolean {
    val seen = HashSet<String>()
    val stack = ArrayDeque(listOf(node))
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (current == other) return true
        if (!seen.add(current)) continue
        links.filter { it.to == current }.forEach { stack += it.from }
    }
    return false
}

private fun ShaderGraph.mapNode(id: String, change: (ShaderGraphNode) -> ShaderGraphNode) =
    copy(nodes = nodes.map { if (it.id == id) change(it) else it })
