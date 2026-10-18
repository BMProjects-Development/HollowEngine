package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver

class DockedScrollRangeTest {
    private fun box(
        vararg mods: Modifier,
        policy: UiMeasurePolicy = UiMeasurePolicies.box(),
        id: String? = null,
    ): BoxNode = BoxNode(id = id, measurePolicy = policy, modifiers = mods.toList())

    private fun col(vararg mods: Modifier, id: String? = null) = box(*mods, policy = UiMeasurePolicies.Column, id = id)

    private fun scrollRangeYWith(shellHeight: UiLength?): Float {
        val scrollable =
            box(Modifier.size(100.percent, 100.percent).then(scrollModifier(horizontal = false)), id = "editor")
        scrollable.children.add(box(Modifier.size(100.percent, 600.px)))
        val editorStack = box(Modifier.grow(1f), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        editorStack.children.add(scrollable)
        val shell = if (shellHeight == null) col(id = "shell")
        else col(Modifier.size(UiLength.Fill, shellHeight), id = "shell")
        shell.children.add(editorStack)
        val contentBox = box(Modifier.size(100.percent, 0.px).grow(1f).clip(), id = "content")
        contentBox.children.add(shell)
        val tabBar = box(Modifier.size(100.percent, 20.px))
        val stackCol = col(Modifier.size(100.percent, 100.percent), id = "stack")
        stackCol.children.add(tabBar); stackCol.children.add(contentBox)
        val dockRoot = box(Modifier.size(100.percent, 100.percent), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        dockRoot.children.add(stackCol)
        val space = box(Modifier.size(100.percent, 100.percent).clip(), id = "space")
        space.children.add(dockRoot)
        UiModifierResolver().resolve(space)
        return UiLayoutPipeline().compute(space, 300f, 300f, UiScrollState()).nodes.getValue(scrollable).scrollRange.y
    }

    private fun scrollRangeY(shellFills: Boolean): Float {
        val scrollable = box(
            Modifier.size(100.percent, 100.percent).then(scrollModifier(horizontal = false)),
            id = "editor",
        )
        scrollable.children.add(box(Modifier.size(100.percent, 600.px))) // tall content

        val editorStack = box(Modifier.grow(1f), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        editorStack.children.add(scrollable)

        val shellMods = if (shellFills) arrayOf(Modifier.size(100.percent, 100.percent)) else arrayOf<Modifier>()
        val shell = col(*shellMods, id = "shell")
        shell.children.add(editorStack)

        val contentBox = box(Modifier.size(100.percent, 0.px).grow(1f).clip(), id = "content")
        contentBox.children.add(shell)

        val tabBar = box(Modifier.size(100.percent, 20.px))
        val stackCol = col(Modifier.size(100.percent, 100.percent), id = "stack")
        stackCol.children.add(tabBar)
        stackCol.children.add(contentBox)

        val dockRoot = box(Modifier.size(100.percent, 100.percent), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        dockRoot.children.add(stackCol)

        val space = box(Modifier.size(100.percent, 100.percent).clip(), id = "space")
        space.children.add(dockRoot)

        UiModifierResolver().resolve(space)
        val layout = UiLayoutPipeline().compute(space, 300f, 300f, UiScrollState())
        return layout.nodes.getValue(scrollable).scrollRange.y
    }

    private fun scrollRangeHssShell(): Float {
        val scrollable =
            box(Modifier.size(100.percent, 100.percent).then(scrollModifier(horizontal = false)), id = "editor")
        scrollable.children.add(box(Modifier.size(100.percent, 600.px)))
        val editorStack = box(Modifier.grow(1f), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        editorStack.children.add(scrollable)
        val shell = col(id = "shell").apply {
            tags.add("ide-editor-shell")
            children.add(editorStack)
        }
        val contentBox = box(Modifier.size(100.percent, 0.px).grow(1f).clip(), id = "content")
        contentBox.children.add(shell)
        val stackCol = col(Modifier.size(100.percent, 100.percent), id = "stack")
        stackCol.children.add(box(Modifier.size(100.percent, 20.px)))
        stackCol.children.add(contentBox)
        val sheet = ru.hollowhorizon.hollowengine.client.ui.style.compileHss(".ide-editor-shell { size: fill fill; }")
        val space = box(Modifier.style(sheet).size(100.percent, 100.percent).clip(), id = "space")
        space.children.add(stackCol)
        UiModifierResolver().resolve(space)
        return UiLayoutPipeline().compute(space, 300f, 300f, UiScrollState()).nodes.getValue(scrollable).scrollRange.y
    }

    private fun scrollRangeNestedScopes(): Float {
        val scrollable =
            box(Modifier.size(100.percent, 100.percent).then(scrollModifier(horizontal = false)), id = "editor")
        scrollable.children.add(box(Modifier.size(100.percent, 600.px)))
        val editorStack = box(Modifier.grow(1f), policy = UiMeasurePolicies.box(UiBoxMode.STACK))
        editorStack.children.add(scrollable)
        val shell = col(id = "shell").also { it.tags.add("ide-editor-shell") }
        shell.children.add(editorStack)
        val contentBox = box(Modifier.size(100.percent, 0.px).grow(1f).clip(), id = "content")
        contentBox.children.add(shell)
        val stackCol = col(Modifier.size(100.percent, 100.percent), id = "stack")
        stackCol.children.add(box(Modifier.size(100.percent, 20.px)))
        stackCol.children.add(contentBox)
        val docking = ru.hollowhorizon.hollowengine.client.ui.style.compileHss(".dock-space { gap: 0px; }")
        val dockSpace = box(
            Modifier.style(docking).size(100.percent, 100.percent).clip(),
            id = "dock"
        ).also { it.tags.add("dock-space") }
        dockSpace.children.add(stackCol)
        val ide = ru.hollowhorizon.hollowengine.client.ui.style.compileHss(".ide-editor-shell { size: fill fill; }")
        val ideRoot = box(Modifier.style(ide).size(100.percent, 100.percent), id = "ide-root")
        ideRoot.children.add(dockSpace)
        UiModifierResolver().resolve(ideRoot)
        return UiLayoutPipeline().compute(ideRoot, 300f, 300f, UiScrollState()).nodes.getValue(scrollable).scrollRange.y
    }

    @Test
    fun `report scroll range for docked nesting`() {
        println("DOCKED nested .style scopes    scrollRange.y = ${scrollRangeNestedScopes()}")
        println("DOCKED HSS shell size:fill fill scrollRange.y = ${scrollRangeHssShell()}")
        println("DOCKED shell=auto     scrollRange.y = ${scrollRangeY(shellFills = false)}")
        println("DOCKED shell=100%%    scrollRange.y = ${scrollRangeY(shellFills = true)}")
        println("DOCKED shell=fill(px) scrollRange.y = ${scrollRangeYWith(100.percent)}")
        println("DOCKED shell=Fill/Fill scrollRange.y = ${scrollRangeYWith(UiLength.Fill)}")
    }
}
