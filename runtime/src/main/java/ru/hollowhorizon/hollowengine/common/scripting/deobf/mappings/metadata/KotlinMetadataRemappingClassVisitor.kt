package ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.metadata

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Remapper

class KotlinMetadataRemappingClassVisitor(private val remapper: Remapper, next: ClassVisitor?) :
    ClassVisitor(Opcodes.ASM9, next) {
    companion object {
        val ANNOTATION_DESCRIPTOR: String = Type.getDescriptor(Metadata::class.java)
    }
    var className: String? = null
    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        this.className = name
        super.visit(version, access, name, signature, superName, interfaces)
    }
    override fun visitAnnotation(
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        var result: AnnotationVisitor? = super.visitAnnotation(descriptor, visible)
        if (descriptor == ANNOTATION_DESCRIPTOR && result != null) {
            try {
                result = KotlinClassMetadataRemappingAnnotationVisitor(remapper, result, className)
            } catch (e: Exception) {
                throw RuntimeException("Failed to remap Kotlin metadata annotation in class $className", e)
            }
        }
        return result
    }
}