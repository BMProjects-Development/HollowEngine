package ru.hollowhorizon.hollowengine.client.models.fbx

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FbxAnimationTest {
    private fun curve(flags: String = "4", attributes: String = "0,0,0,0", times: String = "0,46186158000", values: String = "0,1"): AnimationCurve {
        val doc = fbxDocument("""
            AnimationCurve: 1, "AnimCurve::X", "" {
                KeyTime: *${times.split(',').size} { a: $times }
                KeyValueFloat: *${values.split(',').size} { a: $values }
                KeyAttrFlags: *${flags.split(',').size} { a: $flags }
                KeyAttrDataFloat: *${attributes.split(',').size} { a: $attributes }
            }
        """, "")
        return doc[1]!!.get<AnimationCurve>()!!
    }

    @Test
    fun `curves evaluate linear constant and cubic user tangents`() {
        assertEquals(0.25f, curve().evaluate((FBX_TIME_UNITS_PER_SECOND / 4).toLong()))
        assertEquals(0f, curve("2").evaluate((FBX_TIME_UNITS_PER_SECOND / 2).toLong()))
        assertEquals(1f, curve("258").evaluate((FBX_TIME_UNITS_PER_SECOND / 2).toLong()))
        val cubic = curve("1032", "0,0,0,0")
        assertEquals(0.15625f, cubic.evaluate((FBX_TIME_UNITS_PER_SECOND / 4).toLong()), 1e-6f)
        assertEquals(0.5f, cubic.evaluate((FBX_TIME_UNITS_PER_SECOND / 2).toLong()), 1e-6f)
        val auto = curve("264", "0,0,0,0,0,0,0,0,0,0,0,0", "0,46186158000,92372316000", "0,1,0")
        assertEquals(1f, auto.evaluate(FBX_TIME_UNITS_PER_SECOND.toLong()))
        assertTrue(auto.evaluate((FBX_TIME_UNITS_PER_SECOND / 2).toLong()) > 0.5f)
    }

    @Test
    fun `mixed step and linear axes preserve discontinuities when sampled`() {
        val stepped = curve("2")
        val linear = curve()
        val samples = sampleCurves(listOf(stepped, linear, null), floatArrayOf(0f, 0f, 7f), 0, FBX_TIME_UNITS_PER_SECOND.toLong())
        val before = samples.times.indexOf(Math.nextDown(1f))
        assertTrue(before >= 0)
        assertEquals(0f, samples.values[before][0])
        assertEquals(1f, samples.values.last()[0])
        assertEquals(7f, samples.values.first()[2])
        assertEquals(samples.times.distinct().size, samples.times.size)
    }

    @Test
    fun `a single animated axis keeps the other defaults and uses clip-relative time`() {
        val doc = fbxDocument("""
            Model: 4294967297, "Model::Node", "Null" {
                Properties70: { P: "Lcl Translation", "Lcl Translation", "", "A", 0,5,7 }
            }
            AnimationStack: 10, "AnimStack::Move", "" {
                Properties70: {
                    P: "LocalStart", "KTime", "", "", 46186158000
                    P: "LocalStop", "KTime", "", "", 92372316000
                }
            }
            AnimationLayer: 11, "AnimLayer::Base", "" {}
            AnimationCurveNode: 12, "AnimCurveNode::T", "" {}
            AnimationCurve: 13, "AnimCurve::X", "" {
                KeyTime: *2 { a: 46186158000,92372316000 }
                KeyValueFloat: *2 { a: 0,10 }
            }
        """, """
            C: "OO",4294967297,0
            C: "OO",11,10
            C: "OO",12,11
            C: "OP",12,4294967297,"Lcl Translation"
            C: "OP",13,12,"d|X"
        """)
        val model = doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        val animation = model.animations.single()
        assertEquals("Move", animation.name)
        assertEquals(1f, animation.duration)
        val interpolator = animation.nodes.values.single().translation!!
        assertEquals(Vec3f(5f, 0f, 0f), interpolator.compute(0.5f))
    }

    @Test
    fun `geometric transforms affect meshes without moving model children`() {
        val doc = fbxDocument(fbxMesh() + """
            Model: 1, "Model::Parent", "Mesh" {
                Properties70: {
                    P: "GeometricTranslation", "Vector3D", "", "", 10,0,0
                    P: "Lcl Translation", "Lcl Translation", "", "", 1,0,0
                }
            }
            Model: 2, "Model::Child", "Null" {}
        """, """
            C: "OO",10,1
            C: "OO",1,0
            C: "OO",2,1
        """)
        val model = doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        fun global(index: Int): MutableMat4f {
            val node = model.nodes.single { it.index == index }
            return (node.parent?.let { global(it.index) } ?: MutableMat4f()).mul(node.localMatrix)
        }
        val child = model.nodes.single { it.name == "Child" }
        val mesh = model.nodes.single { it.mesh != null }
        assertEquals(-1f, global(child.index).transform(MutableVec3f()).x)
        assertEquals(-11f, global(mesh.index).transform(MutableVec3f()).x)
    }
    @Test
    fun `in-between morph animation blends adjacent targets`() {
        val doc = fbxDocument(fbxMesh() + """
            Model: 1, "Model::Root", "Mesh" {}
            Deformer: 20, "Deformer::Blend", "BlendShape" {}
            Deformer: 21, "Deformer::Channel", "BlendShapeChannel" {
                DeformPercent: 25
                FullWeights: *2 { a: 50,100 }
            }
            Geometry: 22, "Geometry::Half", "Shape" {
                Indexes: *1 { a: 1 }
                Vertices: *3 { a: 0,0,1 }
            }
            Geometry: 23, "Geometry::Full", "Shape" {
                Indexes: *1 { a: 1 }
                Vertices: *3 { a: 0,0,2 }
            }
            AnimationStack: 30, "AnimStack::Morph", "" {}
            AnimationLayer: 31, "AnimLayer::Base", "" {}
            AnimationCurveNode: 32, "AnimCurveNode::Morph", "" {}
            AnimationCurve: 33, "AnimCurve::Weight", "" {
                KeyTime: *2 { a: 0,46186158000 }
                KeyValueFloat: *2 { a: 0,100 }
            }
        """, """
            C: "OO",1,0
            C: "OO",10,1
            C: "OO",20,10
            C: "OO",21,20
            C: "OO",22,21
            C: "OO",23,21
            C: "OO",31,30
            C: "OO",32,31
            C: "OP",32,21,"DeformPercent"
            C: "OP",33,32,"d|DeformPercent"
        """)
        val model = doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        val node = model.nodes.single { it.mesh != null }
        assertEquals(listOf(0.5f, 0f), node.mesh!!.weights.toList())
        val weights = model.animations.single().nodes.getValue(node.index).weights!!
        assertEquals(listOf(0.5f, 0.5f), weights.compute(0.75f).toList())
    }

    @Test
    fun `unsupported nonuniform scale inheritance fails explicitly`() {
        val doc = fbxDocument("""
            Model: 1, "Model::Parent", "Null" {
                Properties70: { P: "Lcl Scaling", "Lcl Scaling", "", "", 2,1,1 }
            }
            Model: 2, "Model::Child", "Null" {}
        """, """
            C: "OO",1,0
            C: "OO",2,1
        """)
        val error = assertFailsWith<FbxImportException> {
            doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        }
        assertTrue(error.message!!.contains("inheritance"))
    }

}
