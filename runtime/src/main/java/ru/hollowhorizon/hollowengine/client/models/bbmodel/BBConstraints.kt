package ru.hollowhorizon.hollowengine.client.models.bbmodel

import ru.hollowhorizon.hollowengine.client.models.internal.ModelPoseConstraint
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelMatrix
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelRotation
import ru.hollowhorizon.hollowengine.common.models.IkSolver
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

internal object BBConstraints {
    fun convert(nodes: Map<String, BBNode>): List<ModelPoseConstraint> = nodes.values.mapNotNull { controller ->
        val data = controller.element
        if (data.ikTarget.isBlank()) return@mapNotNull null
        val target = requireNotNull(nodes[data.ikTarget]) { "Missing Blockbench IK target ${data.ikTarget}" }
        val source = if (data.ikSource.isBlank()) controller.parent
        else requireNotNull(nodes[data.ikSource]) { "Missing Blockbench IK source ${data.ikSource}" }

        val chain = mutableListOf<BBNode>()
        var current: BBNode? = target
        while (current != source && current != null) {
            chain += current
            current = current.parent
        }

        require(current == source) { "IK source ${data.ikSource} is not an ancestor of ${data.ikTarget}" }

        if (data.ikSource.isNotBlank() && source != null) chain += source
        val pole = data.ikPole.takeIf(String::isNotBlank)?.let { id ->
            requireNotNull(nodes[id]) { "Missing Blockbench IK pole $id" }.index
        }

        BBIKConstraint(controller.index, chain.asReversed().map { it.index }, pole, data.lockIkTargetRotation)
    }
}

private class BBIKConstraint(
    private val controller: Int,
    private val chain: List<Int>,
    private val pole: Int?,
    private val lockEndRotation: Boolean,
) : ModelPoseConstraint {
    override fun apply(nodes: Map<Int, RuntimeNode>) {
        val control = nodes[controller] ?: return
        // Blockbench enables an IK controller only while its animation has keys.
        if (!control.isAnimated || chain.size < 2) return
        val links = chain.map { nodes[it] ?: return }
        val endRotation = links.last().modelRotation()
        links.forEach { link ->
            link.transform.rotation.set(link.definition.baseTransform.rotation)
            link.transform.markDirty()
        }
        val positions = links.map { it.modelMatrix().getTranslation() }
        val solution = IkSolver.solve(
            positions,
            control.modelMatrix().getTranslation(),
            pole?.let { nodes[it]?.modelMatrix()?.getTranslation() },
            IkSolver.rotate(Vec3f.X_AXIS, links.first().modelRotation()),
            maxStretch = 1f,
        )
        val current = positions.map { MutableVec3f(it) }
        val original = links.map { MutableQuatF(it.transform.rotation) }
        var above: QuatF = (links.first().parent as? RuntimeNode)?.modelRotation() ?: QuatF.IDENTITY
        for (i in 0 until links.lastIndex) {
            val turn =
                IkSolver.rotationBetween(current[i + 1] - current[i], solution.joints[i + 1] - solution.joints[i])
            for (j in i + 1..links.lastIndex) {
                current[j].set(current[i] + IkSolver.rotate(current[j] - current[i], turn))
            }
            val global = turn * (above * original[i])
            links[i].transform.rotation.set(above.inverted() * global)
            links[i].transform.markDirty()
            above = global
        }
        if (lockEndRotation) {
            links.last().transform.rotation.set(above.inverted() * endRotation)
            links.last().transform.markDirty()
        }
    }
}
