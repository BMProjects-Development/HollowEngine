package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.parents

/** The declaration's identity survives attaching sources or decompiling a class. */
internal data class SourceDeclarationIdentity(
    val name: String,
    val owners: List<String>,
    val kind: Kind,
    val parameters: List<String> = emptyList(),
    val packageName: String? = null,
) {
    enum class Kind { CLASS, FUNCTION, CONSTRUCTOR, PROPERTY }

    companion object {
        fun from(element: PsiElement): SourceDeclarationIdentity? {
            val identity = when (element) {
                is PsiClass -> element.name?.let { SourceDeclarationIdentity(it, element.javaOwners(), Kind.CLASS) }
                is PsiMethod -> SourceDeclarationIdentity(
                    if (element.isConstructor) element.containingClass?.name ?: element.name else element.name,
                    element.javaOwners(), if (element.isConstructor) Kind.CONSTRUCTOR else Kind.FUNCTION,
                    element.parameterList.parameters.map { it.type.canonicalText },
                )
                is PsiField -> SourceDeclarationIdentity(element.name, element.javaOwners(), Kind.PROPERTY)
                is KtClassOrObject -> element.name?.let { SourceDeclarationIdentity(it, element.kotlinOwners(), Kind.CLASS) }
                is KtConstructor<*> -> SourceDeclarationIdentity(
                    element.getContainingClassOrObject().name ?: return null,
                    element.kotlinOwners(), Kind.CONSTRUCTOR,
                    element.valueParameters.map { it.typeReference.sourceType() + if (it.isVarArg) "[]" else "" },
                )
                is KtNamedFunction -> element.name?.let { name ->
                    SourceDeclarationIdentity(name, element.kotlinOwners(), Kind.FUNCTION,
                        listOfNotNull(element.receiverTypeReference?.sourceType()) + element.valueParameters.map {
                            it.typeReference.sourceType() + if (it.isVarArg) "[]" else ""
                        })
                }
                is KtProperty -> element.name?.let { SourceDeclarationIdentity(it, element.kotlinOwners(), Kind.PROPERTY) }
                is KtTypeAlias -> element.name?.let { SourceDeclarationIdentity(it, element.kotlinOwners(), Kind.CLASS) }
                else -> null
            }
            val packageName = when (val file = element.containingFile) {
                is KtFile -> file.packageFqName.asString()
                is PsiClassOwner -> file.packageName
                else -> null
            }
            return identity?.copy(packageName = packageName)
        }
    }
}

internal class SourceDeclarationMatcher(private val project: Project) {
    fun offset(fileName: String, text: String, target: SourceDeclarationIdentity): Int? {
        val candidates = if (fileName.endsWith(".java")) javaDeclarations(fileName, text) else kotlinDeclarations(fileName, text)
        val named = candidates.filter { it.identity.name == target.name && it.identity.kind == target.kind &&
            it.identity.owners == target.owners && (target.packageName == null || target.packageName == it.identity.packageName) }
        val exact = named.filter { candidate ->
            candidate.identity.parameters.map(::normalizedSourceType) == target.parameters.map(::normalizedSourceType)
        }
        if (exact.size == 1) return exact.single().offset
        if (exact.size > 1) return null
        // Unresolved source PSI can spell a type with a short name. Only accept a unique signature.
        val short = named.filter { candidate ->
            candidate.identity.parameters.size == target.parameters.size &&
                candidate.identity.parameters.zip(target.parameters).all { (source, binary) ->
                    val left = normalizedSourceType(source)
                    val right = normalizedSourceType(binary)
                    left == right || ('.' !in left || '.' !in right) && left.substringAfterLast('.') == right.substringAfterLast('.')
                }
        }
        return short.singleOrNull()?.offset
    }

    private fun javaDeclarations(fileName: String, text: String): List<SourceDeclaration> {
        val file = PsiFileFactory.getInstance(project).createFileFromText(fileName.substringAfterLast('/'), JavaFileType.INSTANCE, text)
        val declarations = PsiTreeUtil.collectElementsOfType(file, PsiNameIdentifierOwner::class.java).mapNotNull { element ->
            val identity = SourceDeclarationIdentity.from(element) ?: return@mapNotNull null
            element.nameIdentifier?.textRange?.startOffset?.let { SourceDeclaration(identity, it) }
        }.toMutableList()
        PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java).filterNot { it.isInterface || it.isEnum }.forEach { declaration ->
            val identity = SourceDeclarationIdentity.from(declaration) ?: return@forEach
            val owners = identity.owners + identity.name
            if (declarations.any { it.identity.kind == SourceDeclarationIdentity.Kind.CONSTRUCTOR && it.identity.owners == owners }) return@forEach
            val constructor = identity.copy(kind = SourceDeclarationIdentity.Kind.CONSTRUCTOR, owners = owners,
                parameters = declaration.recordComponents.map { it.type.canonicalText })
            declaration.nameIdentifier?.textRange?.startOffset?.let { declarations += SourceDeclaration(constructor, it) }
        }
        return declarations
    }

    private fun kotlinDeclarations(fileName: String, text: String): List<SourceDeclaration> {
        val file = KtPsiFactory(project).createFile(fileName.substringAfterLast('/'), text)
        val declarations = PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java).mapNotNull { declaration ->
            val identity = SourceDeclarationIdentity.from(declaration) ?: return@mapNotNull null
            declaration.nameIdentifier?.textRange?.startOffset?.let { SourceDeclaration(identity, it) }
        }.toMutableList()
        PsiTreeUtil.collectElementsOfType(file, KtSecondaryConstructor::class.java).forEach { constructor ->
            val identity = SourceDeclarationIdentity.from(constructor) ?: return@forEach
            declarations += SourceDeclaration(identity, constructor.getConstructorKeyword().textRange.startOffset)
        }
        // Primary constructors have no own name token: navigate to the class name.
        PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).forEach { declaration ->
            val klass = declaration as? KtClass ?: return@forEach
            val constructor = klass.primaryConstructor
            val identity = if (constructor != null) SourceDeclarationIdentity.from(constructor)
                else if (klass.secondaryConstructors.isEmpty() && !klass.isInterface()) {
                    SourceDeclarationIdentity.from(klass)?.let { it.copy(kind = SourceDeclarationIdentity.Kind.CONSTRUCTOR, owners = it.owners + it.name) }
                } else null
            if (identity == null) return@forEach
            declaration.nameIdentifier?.textRange?.startOffset?.let { declarations += SourceDeclaration(identity, it) }
        }
        return declarations
    }
}

private data class SourceDeclaration(val identity: SourceDeclarationIdentity, val offset: Int)

private fun PsiElement.javaOwners(): List<String> = generateSequence(parent) { it.parent }
    .filterIsInstance<PsiClass>().mapNotNull { it.name }.toList().asReversed()

private fun PsiElement.kotlinOwners(): List<String> = parents.filterIsInstance<KtClassOrObject>()
    .mapNotNull { it.name }.toList().asReversed()

private fun KtTypeReference?.sourceType(): String {
    fun functionType(element: KtTypeElement?): KtFunctionType? = when (element) {
        is KtFunctionType -> element
        is KtNullableType -> functionType(element.innerType)
        else -> null
    }
    val function = functionType(this?.typeElement)
    if (function != null) return "kotlin.jvm.functions.Function${function.parameters.size + if (function.receiverTypeReference != null) 1 else 0}"
    val reference = this ?: return ""
    val type = (reference.typeElement as? KtNullableType)?.innerType ?: reference.typeElement
    val userType = type as? KtUserType ?: return reference.text
    if (userType.qualifier != null) return reference.text
    val imported = reference.containingKtFile.importDirectives.firstOrNull {
        (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == userType.referencedName
    }?.importedFqName?.asString() ?: return reference.text
    val name = userType.referenceExpression?.textRange ?: return reference.text
    return reference.text.replaceRange(name.startOffset - reference.textRange.startOffset,
        name.endOffset - reference.textRange.startOffset, imported)
}

/** Compare erased source signatures, preserving array rank and mapping Kotlin/JVM built-ins. */
internal fun normalizedSourceType(type: String): String {
    val clean = type.trim().replace("...", "[]")
    val arrayType = clean.removeSuffix("?").removePrefix("kotlin.")
    if (arrayType.startsWith("Array<") && arrayType.endsWith('>')) {
        val element = arrayType.substringAfter('<').dropLast(1).removePrefix("out ").removePrefix("in ")
        val boxed = BoxedTypes[element.removePrefix("kotlin.")] ?: normalizedSourceType(element)
        return "$boxed[]"
    }
    var depth = 0
    val erased = buildString {
        for (char in clean) {
            when (char) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0 && !char.isWhitespace() && char != '?' && char != '!') append(char)
            }
        }
    }.replace('$', '.')
    val base = erased.substringBefore('[').removePrefix("kotlin.")
    val mapped = if (clean.endsWith('?') && base in BoxedTypes) BoxedTypes.getValue(base) else JvmTypes[base] ?: base
    return mapped + erased.substringAfter(base, "").takeIf { it.startsWith("[]") }.orEmpty()
}

private val JvmTypes = mapOf(
    "Int" to "int", "Long" to "long", "Short" to "short", "Byte" to "byte", "Boolean" to "boolean",
    "Char" to "char", "Float" to "float", "Double" to "double", "Unit" to "void",
    "String" to "java.lang.String", "Any" to "java.lang.Object", "CharSequence" to "java.lang.CharSequence",
    "IntArray" to "int[]", "LongArray" to "long[]", "ByteArray" to "byte[]", "BooleanArray" to "boolean[]",
    "CharArray" to "char[]", "ShortArray" to "short[]", "FloatArray" to "float[]", "DoubleArray" to "double[]",
    "collections.List" to "java.util.List", "List" to "java.util.List",
    "collections.MutableList" to "java.util.List", "MutableList" to "java.util.List",
    "collections.Set" to "java.util.Set", "Set" to "java.util.Set",
    "collections.MutableSet" to "java.util.Set", "MutableSet" to "java.util.Set",
    "collections.Map" to "java.util.Map", "Map" to "java.util.Map",
    "collections.MutableMap" to "java.util.Map", "MutableMap" to "java.util.Map",
    "collections.Collection" to "java.util.Collection", "Collection" to "java.util.Collection",
    "collections.MutableCollection" to "java.util.Collection", "MutableCollection" to "java.util.Collection",
    "collections.Iterable" to "java.lang.Iterable", "Iterable" to "java.lang.Iterable",
    "collections.MutableIterable" to "java.lang.Iterable", "MutableIterable" to "java.lang.Iterable",
)

private val BoxedTypes = mapOf(
    "Int" to "java.lang.Integer", "Long" to "java.lang.Long", "Short" to "java.lang.Short",
    "Byte" to "java.lang.Byte", "Boolean" to "java.lang.Boolean", "Char" to "java.lang.Character",
    "Float" to "java.lang.Float", "Double" to "java.lang.Double",
)
