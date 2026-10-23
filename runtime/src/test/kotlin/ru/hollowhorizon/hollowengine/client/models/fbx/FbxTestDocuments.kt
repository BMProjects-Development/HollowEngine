package ru.hollowhorizon.hollowengine.client.models.fbx

internal fun fbxDocument(objects: String, connections: String): Document = FbxModelLoader.parse("""
    FBXHeaderExtension: { FBXVersion: 7400 }
    Definitions: {}
    GlobalSettings: { Properties70: { P: "UnitScaleFactor", "double", "", "", 100 } }
    Objects: { $objects }
    Connections: { $connections }
""".trimIndent().toByteArray())

internal fun fbxMesh(id: Long = 10, extra: String = "", vertices: String = "0,0,0,1,0,0,0,1,0", indices: String = "0,1,-3"): String = """
    Geometry: $id, "Geometry::Mesh", "Mesh" {
        Vertices: *${vertices.split(',').size} { a: $vertices }
        PolygonVertexIndex: *${indices.split(',').size} { a: $indices }
        $extra
    }
""".trimIndent()

internal const val IDENTITY_VALUES = "1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1"
