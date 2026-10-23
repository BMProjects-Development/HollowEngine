package ru.hollowhorizon.hollowengine.client.models.fbx

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FbxGeometryTest {
    @Test
    fun `mixed polygons resolve indexed attributes and per-face materials`() {
        val geometry = fbxMesh(vertices = "0,0,0,1,0,0,1,1,0,0,2,0,-1,1,0", indices = "0,1,-3,0,2,3,-5", extra = """
            LayerElementNormal: 0 {
                MappingInformationType: "ByPolygon"
                ReferenceInformationType: "IndexToDirect"
                Normals: *6 { a: 0,0,1,0,1,0 }
                NormalsIndex: *2 { a: 1,0 }
            }
            LayerElementUV: 0 {
                MappingInformationType: "ByVertice"
                ReferenceInformationType: "IndexToDirect"
                UV: *10 { a: 0,0,1,0,1,1,0,1,0.5,0.5 }
                UVIndex: *5 { a: 4,3,2,1,0 }
            }
            LayerElementMaterial: 0 {
                MappingInformationType: "ByPolygon"
                ReferenceInformationType: "IndexToDirect"
                Materials: *2 { a: 1,0 }
            }
        """)
        val doc = fbxDocument(geometry, "")
        val mesh = assertNotNull(doc[10]!!.get<MeshGeometry>())
        assertEquals(7, mesh.vertices.size)
        assertEquals(9, mesh.indices.size)
        assertEquals(listOf(1, 0, 0), mesh.materials)
        assertEquals(List(3) { Vec3f.Y_AXIS } + List(4) { Vec3f.Z_AXIS }, mesh.normals)
        assertEquals(listOf(0, 1, 2, 0, 2, 3, 4), mesh.vertexControlPoints)
        assertEquals(listOf(Vec2f(0.5f, 0.5f), Vec2f(0f, 1f), Vec2f(1f, 1f), Vec2f(0.5f, 0.5f), Vec2f(1f, 1f), Vec2f(1f, 0f), Vec2f.ZERO), mesh.uvs[0])
    }

    @Test
    fun `missing optional attributes and materials use runtime defaults`() {
        val doc = fbxDocument(fbxMesh() + "\nModel: 1, \"Model::Root\", \"Mesh\" {}", """
            C: "OO",10,1
            C: "OO",1,0
        """)
        val model = doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        val primitive = model.nodes.flatMap { it.mesh?.primitives.orEmpty() }.single()
        assertNull(primitive.normals)
        assertNull(primitive.texCoords)
        assertNull(primitive.joints)
        primitive.prepareGeometry()
        assertEquals(3, assertNotNull(primitive.normals).size)
    }

    @Test
    fun `weights keep strongest influences and expand from control points`() {
        val bones = (1..6).joinToString("\n") { "Model: ${4294967296L + it}, \"Model::Joint$it\", \"LimbNode\" {}" }
        val clusters = (1..6).joinToString("\n") { """
            Deformer: ${100 + it}, "SubDeformer::Cluster$it", "Cluster" {
                Indexes: *1 { a: 0 }
                Weights: *1 { a: ${it * 0.1} }
                Transform: *16 { a: $IDENTITY_VALUES }
                TransformLink: *16 { a: $IDENTITY_VALUES }
            }
        """ }
        val links = (1..6).joinToString("\n") { """
            C: "OO",${4294967296L + it},0
            C: "OO",${4294967296L + it},${100 + it}
            C: "OO",${100 + it},20
        """ }
        val doc = fbxDocument(fbxMesh() + "\n$clusters\n$bones\nDeformer: 20, \"Deformer::Skin\", \"Skin\" {}\nModel: 1, \"Model::Mesh\", \"Mesh\" {}", """
            $links
            C: "OO",20,10
            C: "OO",10,1
            C: "OO",1,0
        """)
        val model = doc.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER)
        val node = model.nodes.single { it.skin != null }
        val joints = node.mesh!!.primitives.single().joints!!
        val weights = node.mesh.primitives.single().jointWeights!!
        assertEquals(listOf(5, 4, 3, 2), listOf(joints[0].x, joints[0].y, joints[0].z, joints[0].w))
        assertEquals(0.6f / 1.8f, weights[0].x, 1e-6f)
        assertEquals(node.index, node.skin!!.jointsIds[joints[1].x])
        assertEquals(1f, weights[1].x)
        assertEquals(model.nodes.size, model.nodes.map { it.index }.distinct().size)
    }

    @Test
    fun `sparse morph targets expand at every corner sharing a control point`() {
        val doc = fbxDocument(fbxMesh(indices = "0,1,-3,2,1,-1") + """
            Deformer: 20, "Deformer::Blend", "BlendShape" {}
            Deformer: 21, "Deformer::Channel", "BlendShapeChannel" {
                Properties70: { P: "DeformPercent", "double", "", "",50 }
            }
            Geometry: 22, "Geometry::Shape", "Shape" {
                Indexes: *1 { a: 1 }
                Vertices: *3 { a: 0,0,2 }
            }
        """, """
            C: "OO",22,21
            C: "OO",21,20
            C: "OO",20,10
        """)
        val mesh = doc[10]!!.get<MeshGeometry>()!!
        val deltas = morphTargets(mesh).single().getValue("POSITION")
        assertEquals(18, deltas.size)
        assertEquals(2f, deltas[1 * 3 + 2])
        assertEquals(2f, deltas[4 * 3 + 2])
        assertTrue(deltas.withIndex().all { (i, value) -> value == if (i == 5 || i == 14) 2f else 0f })
        assertEquals(0.5f, morphWeights(mesh).single())
    }
}
