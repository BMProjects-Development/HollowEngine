package ru.hollowhorizon.hollowengine.client.render

/** Additional shadow renderers use the same effect suppression and world transforms as Iris shadows. */
object RenderPasses {
    private var depth = 0
    val isShadowPass: Boolean get() = depth > 0

    fun <T> shadow(block: () -> T): T {
        depth++
        return try { block() } finally { depth-- }
    }
}
