package ru.hollowhorizon.hollowengine.fabric.internal.rendering

import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry.Companion.SLOT_ATTRIBUTE
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry.Companion.SLOT_COUNT_UNIFORM
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry.Companion.TRANSFORMS_UNIFORM
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticInstanceData

object IrisInstancingShaderPatcher {
    private val versionPattern = Regex("#version\\s+(\\d+)([^\\n]*)")
    private val extensionPattern = Regex("(?m)^\\s*#extension[^\\n]*$")

    fun patch(source: String): String {
        val match = versionPattern.find(source) ?: return source
        val version = match.groupValues[1].toIntOrNull() ?: return source
        val attributeQualifier = if (version >= 130) "in" else "attribute"

        val header = buildString {
            appendLine()
            appendLine("$attributeQualifier vec3 Position;")
            appendLine("$attributeQualifier vec2 UV0;")
            appendLine("$attributeQualifier vec3 Normal;")
            appendLine("$attributeQualifier float $SLOT_ATTRIBUTE;")
            appendLine("uniform samplerBuffer $TRANSFORMS_UNIFORM;")
            appendLine("uniform int $SLOT_COUNT_UNIFORM;")
            appendLine("int _he_InstanceBase() {")
            appendLine("    return gl_InstanceID * (${StaticInstanceData.HEADER_TEXELS} + ${StaticInstanceData.SLOT_TEXELS} * $SLOT_COUNT_UNIFORM);")
            appendLine("}")
            appendLine("mat4 _he_SlotMat() {")
            appendLine("    int at = _he_InstanceBase() + ${StaticInstanceData.HEADER_TEXELS} + int($SLOT_ATTRIBUTE + 0.5) * ${StaticInstanceData.SLOT_TEXELS};")
            appendLine("    return mat4(texelFetch($TRANSFORMS_UNIFORM, at), texelFetch($TRANSFORMS_UNIFORM, at + 1), texelFetch($TRANSFORMS_UNIFORM, at + 2), texelFetch($TRANSFORMS_UNIFORM, at + 3));")
            appendLine("}")
            appendLine("mat4 _he_ModelViewMat() {")
            appendLine("    int base = _he_InstanceBase();")
            appendLine("    return mat4(texelFetch($TRANSFORMS_UNIFORM, base), texelFetch($TRANSFORMS_UNIFORM, base + 1),")
            appendLine("        texelFetch($TRANSFORMS_UNIFORM, base + 2), texelFetch($TRANSFORMS_UNIFORM, base + 3)) * _he_SlotMat();")
            appendLine("}")
            appendLine("mat4 _he_ModelViewProjectionMat() {")
            appendLine("    return gl_ProjectionMatrix * _he_ModelViewMat();")
            appendLine("}")
            appendLine("mat3 _he_NormalMat() {")
            appendLine("    int base = _he_InstanceBase();")
            appendLine("    mat3 instanceNormal = mat3(texelFetch($TRANSFORMS_UNIFORM, base + 4).xyz, texelFetch($TRANSFORMS_UNIFORM, base + 5).xyz,")
            appendLine("        texelFetch($TRANSFORMS_UNIFORM, base + 6).xyz);")
            appendLine("    mat3 slot = mat3(_he_SlotMat());")
            appendLine("    float det = determinant(slot);")
            appendLine("    if (abs(det) < 1e-8) return instanceNormal;")
            appendLine("    return instanceNormal * transpose(inverse(slot)) * pow(abs(det), 1.0 / 3.0);")
            appendLine("}")
            appendLine("vec4 _he_Vertex() {")
            appendLine("    return vec4(Position, 1.0);")
            appendLine("}")
        }

        val insertionIndex =
            extensionPattern.findAll(source).lastOrNull()?.range?.last?.plus(1) ?: (match.range.last + 1)
        var patched = source.substring(0, insertionIndex) + header + source.substring(insertionIndex)

        patched = patched.replace("ftransform()", "(_he_ModelViewProjectionMat() * _he_Vertex())")
        patched = replaceToken(patched, "gl_ModelViewProjectionMatrixInverse", "inverse(_he_ModelViewProjectionMat())")
        patched = replaceToken(patched, "gl_ModelViewProjectionMatrix", "_he_ModelViewProjectionMat()")
        patched = replaceToken(patched, "gl_ModelViewMatrixInverseTranspose", "transpose(inverse(_he_ModelViewMat()))")
        patched = replaceToken(patched, "gl_ModelViewMatrixInverse", "inverse(_he_ModelViewMat())")
        patched = replaceToken(patched, "gl_ModelViewMatrix", "_he_ModelViewMat()")
        patched = replaceToken(patched, "gl_NormalMatrix", "_he_NormalMat()")
        patched = replaceToken(patched, "gl_Vertex", "_he_Vertex()")
        patched = replaceToken(patched, "gl_Normal", "Normal")
        patched = replaceToken(patched, "gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)")

        return patched
    }

    private fun replaceToken(source: String, token: String, replacement: String): String {
        return Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").replace(source, replacement)
    }
}
