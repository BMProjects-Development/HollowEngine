import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.vfx.VfxExpressionLanguage
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxAppearance
import ru.hollowhorizon.hollowengine.common.vfx.VfxBlend
import ru.hollowhorizon.hollowengine.common.vfx.VfxColorValue
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurveInput
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmission
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat
import ru.hollowhorizon.hollowengine.common.vfx.VfxGradient
import ru.hollowhorizon.hollowengine.common.vfx.VfxGradientStop
import ru.hollowhorizon.hollowengine.common.vfx.VfxGroupSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxMaterialSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPostEffectSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxRgba
import ru.hollowhorizon.hollowengine.common.vfx.VfxSamplerSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxShape
import ru.hollowhorizon.hollowengine.common.vfx.VfxShapeKind
import ru.hollowhorizon.hollowengine.common.vfx.VfxSpawn
import ru.hollowhorizon.hollowengine.common.vfx.VfxSphereSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxTimelineSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrack
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrailSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxUniformValue
import ru.hollowhorizon.hollowengine.common.vfx.VfxUvRect
import ru.hollowhorizon.hollowengine.common.vfx.VfxValue
import ru.hollowhorizon.hollowengine.common.vfx.VfxVec3Value
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxForceKind
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxForceSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxNoiseSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxUvAnimationSpec
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `.vfx` format has to survive a round trip.
 *
 * Effects are written by an editor that rewrites the whole file, so anything the reader drops is
 * work silently thrown away. That makes the round trip the one test worth keeping here: it covers
 * the polymorphic node and module registries, the sealed value shapes and the timeline at once.
 */
class VfxFormatTests {
    @Test
    fun `an effect survives a round trip`() {
        val effect = sample()

        val text = VfxFormat.write(effect)
        val read = VfxFormat.read(text)

        assertEquals(effect, read)
    }

    @Test
    fun `a track names its property by id`() {
        val text = VfxFormat.write(sample())

        assertTrue("\"property\": \"uniforms.FieldColor\"" in text, "A property id is not written as a plain string")
    }

    @Test
    fun `an empty file reads as an empty effect`() {
        assertEquals(VfxEffect.EMPTY, VfxFormat.read(""))
    }

    @Test
    fun `the shipped effects read and compile`() {
        val folder = javaClass.classLoader.getResource(EXAMPLES_PATH)?.toURI()?.let(::File)
            ?: error("The example effects are missing from the resources: $EXAMPLES_PATH")
        val files = folder.listFiles { file -> file.name.endsWith(VfxFormat.EXTENSION) }.orEmpty()
        assertTrue(files.isNotEmpty(), "No example effects in $EXAMPLES_PATH")

        files.forEach { file ->
            val text = file.readText().replace("\r\n", "\n")
            val effect = VfxFormat.read(text)

            assertTrue(effect.nodes.isNotEmpty(), "${file.name} has no nodes")
            assertEquals(text.trim(), VfxFormat.write(effect).trim(), "${file.name} is not in canonical form")

            val sources = effect.expressions()
            if (sources.isEmpty()) return@forEach
            val diagnostics = VfxExpressionLanguage.compile(sources).diagnostics
            assertTrue(diagnostics.isEmpty(), "${file.name} has expressions that do not compile: $diagnostics")
        }
    }

    private fun sample() = VfxEffect(
        nodes = listOf(
            VfxGroupSpec(
                id = "core",
                name = "Core",
                transform = VfxTransform(position = Vec3f(0f, 0.25f, 0f)),
                children = listOf(flame(), field()),
            ),
            VfxEmitterSpec(
                id = "smoke",
                name = "Smoke",
                modules = listOf(VfxNoiseSpec(strength = VfxValue.Const(0.5f))),
            ),
            VfxPostEffectSpec(id = "gray"),
        ),
        timeline = VfxTimelineSpec(
            duration = 3f,
            tracks = listOf(
                VfxTrack(
                    node = "smoke",
                    property = VfxProperty.ENABLED,
                    curves = listOf(VfxTrackCurve(keys = listOf(VfxKey(0f, 0f), VfxKey(1f, 1f)))),
                ),
                VfxTrack(
                    node = "flame",
                    property = VfxProperty.module("swirl", "strength"),
                    curves = listOf(VfxTrackCurve(keys = listOf(VfxKey(0f, 0f), VfxKey(2f, 3f)))),
                ),
                VfxTrack(
                    node = "field",
                    property = VfxProperty.uniform("FieldColor"),
                    curves = listOf(VfxTrackCurve(channel = 3, keys = listOf(VfxKey(0f, 1f), VfxKey(1f, 0f)))),
                ),
            ),
        ),
    )

    private fun flame() = VfxEmitterSpec(
        id = "flame",
        name = "Flame",
        emission = VfxEmission(rate = VfxValue.Const(40f), burst = 3, duration = 0f),
        shape = VfxShape(
            kind = VfxShapeKind.CONE,
            radius = VfxValue.Expr("0.1 + e.age * 0.05"),
            angle = VfxValue.Const(12f),
        ),
        spawn = VfxSpawn(
            lifetime = VfxValue.Range(0.4f, 0.9f),
            speed = VfxValue.Range(0.5f, 1.2f),
        ),
        modules = listOf(
            VfxForceSpec(id = "swirl", kind = VfxForceKind.VORTEX, strength = VfxValue.Expr("p.progress * 2")),
            VfxForceSpec(id = "wind", direction = Vec3f(1f, 0f, 0f)),
            VfxUvAnimationSpec(columns = 2, rows = 2),
        ),
        children = listOf(
            VfxPlaneSpec(
                id = "flame-plane",
                material = VfxMaterialSpec(blend = VfxBlend.ADDITIVE, uv = VfxUvRect(0f, 0f, 0.5f, 0.5f)),
                particle = VfxAppearance(
                    size = VfxVec3Value(
                        VfxValue.OverTime(VfxCurve(listOf(VfxKey(0f, 0.2f), VfxKey(1f, 0f))), input = VfxCurveInput.SPEED),
                        VfxValue.ONE,
                        VfxValue.ONE,
                    ),
                    color = VfxColorValue.Gradient(
                        VfxGradient(
                            listOf(
                                VfxGradientStop(0f, VfxRgba(1f, 1f, 1f, 1f)),
                                VfxGradientStop(1f, VfxRgba(1f, 0.2f, 0f, 0f)),
                            )
                        )
                    ),
                ),
            ),
            VfxTrailSpec(id = "flame-trail", maxPoints = 12),
        ),
    )

    private fun field() = VfxSphereSpec(
        id = "field",
        segments = 32,
        material = VfxMaterialSpec(
            shader = "hollowengine:vfx/force_field",
            uniforms = listOf(
                VfxUniformSpec("FieldColor", VfxUniformValue.Color(VfxColorValue.Solid(VfxRgba(0.3f, 0.7f, 1f)))),
                VfxUniformSpec("FresnelPower", VfxUniformValue.Scalar(VfxValue.Expr("2 + n.camera_distance"))),
                VfxUniformSpec("Offset", VfxUniformValue.Vector(VfxVec3Value.all(0.5f))),
            ),
            samplers = listOf(VfxSamplerSpec("Pattern", "hollowengine:textures/particle/circle.png")),
        ),
    )

    private companion object {
        const val EXAMPLES_PATH = "assets/hollowengine/vfx"
    }
}
