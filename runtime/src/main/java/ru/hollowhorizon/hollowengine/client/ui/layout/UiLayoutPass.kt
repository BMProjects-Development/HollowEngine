package ru.hollowhorizon.hollowengine.client.ui.layout


import ru.hollowhorizon.hollowengine.client.ui.UiNode
import java.util.*

internal class LayoutPass(root: UiNode) {
    val layoutChildren = IdentityHashMap<UiNode, List<UiNode>>()

    init {
        val stack = ArrayDeque<UiNode>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val children = ArrayList(node.children)
            layoutChildren[node] = children
            for (index in children.indices.reversed()) {
                stack.add(children[index])
            }
        }
    }
}
