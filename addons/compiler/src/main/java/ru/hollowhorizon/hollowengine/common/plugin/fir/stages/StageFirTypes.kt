package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageValueTypes

internal object StageFirIds {
    val PACKAGE = FqName("ru.hollowhorizon.hollowengine.addons.narrative.story")
    val STATE = ClassId.topLevel(PACKAGE.child(Name.identifier("State")))
    val ACTION = ClassId.topLevel(PACKAGE.child(Name.identifier("Action")))
    val ACTION_ALIAS = ClassId.topLevel(PACKAGE.child(Name.identifier("ActionAlias")))
    val UNSAVED =ClassId.topLevel(PACKAGE.child(Name.identifier("Unsaved")))
    val CHECKPOINT_SCOPE = ClassId.topLevel(PACKAGE.child(Name.identifier("CheckpointScope")))
}

internal fun ConeKotlinType.isStageLambda(session: FirSession): Boolean =
    customAnnotations.any { it.toAnnotationClassIdSafe(session) == StageFirIds.STATE } ||
            classId?.asSingleFqName()?.asString().orEmpty().startsWith(StageValueTypes.STAGE_LAMBDA)

/** Type policy agrees with the IR checker; error types are left to Kotlin's own diagnostics. */
internal fun ConeKotlinType.stageProblem(session: FirSession): String? = stageProblem(session, HashSet())

/** @param records the records being checked further up, so a record that holds itself is not checked forever. */
@OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
private fun ConeKotlinType.stageProblem(session: FirSession, records: MutableSet<ClassId>): String? {
    if (this is ConeErrorType || isStageLambda(session)) return null
    val klass = toRegularClassSymbol(session) ?: return "$this is a type parameter, which cannot be saved"
    val name = klass.classId.asSingleFqName().asString()
    if (name in StageValueTypes.BASIC || name in StageValueTypes.PRIMITIVE_ARRAYS || name == StageValueTypes.STORY_HANDLE) return null
    fun root(type: ConeKotlinType, visited: MutableSet<ClassId> = HashSet()): String? {
        val symbol = type.toRegularClassSymbol(session) ?: return null
        if (!visited.add(symbol.classId)) return null
        val qualified = symbol.classId.asSingleFqName().asString()
        if (qualified == "net.minecraft.world.entity.Entity" || qualified == "net.minecraft.nbt.Tag" || qualified == StageValueTypes.STORY_RECORD) return qualified
        return symbol.resolvedSuperTypes.firstNotNullOfOrNull { root(it, visited) }
    }
    when (root(this)) {
        null -> Unit
        StageValueTypes.STORY_RECORD -> {
            if (!records.add(klass.classId)) return null
            val constructor = klass.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull { it.isPrimary }
            return constructor?.valueParameterSymbols.orEmpty().firstNotNullOfOrNull { field ->
                field.resolvedReturnType.stageProblem(session, records)?.let { "its field '${field.name}' cannot be saved: $it" }
            }
        }
        else -> return null
    }
    if (name == "kotlin.Array" || name in StageValueTypes.CONTAINERS || name in StageValueTypes.CONTEXTUAL ||
        klass.classKind == ClassKind.ENUM_CLASS || klass.hasAnnotation(StageValueTypes.SERIALIZABLE_ID, session)
    ) {
        return typeArguments.firstNotNullOfOrNull {
            (it as? ConeKotlinTypeProjection)?.type?.stageProblem(session, records) ?: if (it is ConeKotlinTypeProjection) null
            else "a star projection cannot be saved"
        }
    }
    if (name.startsWith("kotlin.Function") || name.startsWith("kotlin.coroutines.SuspendFunction") || name.startsWith("kotlin.reflect.KFunction")) {
        return "a function value cannot be saved; pass a lambda to a @State parameter instead"
    }
    return "$this cannot be saved: it is not @Serializable"
}
