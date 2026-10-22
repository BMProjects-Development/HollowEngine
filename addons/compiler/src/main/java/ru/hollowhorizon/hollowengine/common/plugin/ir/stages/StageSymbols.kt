package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.plugin.ir.stages.StageSymbols.Companion.find

/**
 * The declarations of the story runtime that stage code is rewritten against. The runtime lives in the
 * Narrative addon; without it on classpath there are no stages to compile and [find] returns null.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageSymbols private constructor(context: IrPluginContext, val state: IrClassSymbol) {
    private val finder = context.finderForBuiltins()
    val builtIns = context.irBuiltIns

    val frame: IrClassSymbol = storyClass("StoryFrame")
    val frames: IrClassSymbol = storyClass("StoryFrames")
    val values: IrClassSymbol = storyClass("StoryValues")
    val instances: IrClassSymbol = storyClass("StoryInstances")
    val loops: IrClassSymbol = storyClass("StoryLoops")
    val stageLambda: IrClassSymbol = storyClass("StageLambda")
    val storyBox: IrClassSymbol = storyClass("StoryBox")
    val boxes: IrClassSymbol = storyClass("StoryBoxes")
    val box: IrSimpleFunctionSymbol = member(boxes, "box")
    val boxGetter: IrSimpleFunctionSymbol = boxValue().getter!!.symbol
    val boxSetter: IrSimpleFunctionSymbol = boxValue().setter!!.symbol
    val stageEntry: IrConstructorSymbol = storyClass("StageEntry").constructors.single()
    val stageCall: IrConstructorSymbol = storyClass("StageCall").constructors.single()
    val stageResult: IrConstructorSymbol = storyClass("StageResult").constructors.single()
    val stageScript: IrConstructorSymbol = storyClass("StageScript").constructors.single()
    val stateConstructor: IrConstructorSymbol = state.constructors.single()
    val checkpoints: Set<IrSimpleFunctionSymbol> = storyFunctions("checkpoint")
    val actionBodies: Set<IrSimpleFunctionSymbol> = storyFunctions("action") + storyFunctions("label")
    val checkpointScope: IrClassSymbol = storyClass("CheckpointScope")
    val storySerializer: IrSimpleFunctionSymbol = storyFunction("storySerializer")

    val compoundTag: IrClassSymbol = finder.findClass(ClassId.topLevel(FqName("net.minecraft.nbt.CompoundTag")))
        ?: error("CompoundTag is not on the script classpath")
    val compoundTagConstructor: IrConstructorSymbol =
        compoundTag.constructors.single { constructor -> constructor.owner.parameters.none { it.kind == IrParameterKind.Regular } }

    /** `KSerializer<Any?>`, what [storySerializer] returns. */
    val serializerType: IrType =
        finder.findClass(ClassId.topLevel(FqName("kotlinx.serialization.KSerializer")))?.typeWith(builtIns.anyNType)
            ?: error("kotlinx.serialization is not on the script classpath")

    val frameType: IrType get() = frame.owner.defaultType
    val sequence: IrClassSymbol? = finder.findClass(ClassId.topLevel(FqName("kotlin.sequences.Sequence")))
    val compoundTagType: IrType get() = compoundTag.owner.defaultType
    val intProgression: IrClassSymbol? = finder.findClass(ClassId.topLevel(FqName("kotlin.ranges.IntProgression")))

    /** The member [name] of the frame that takes [arity] arguments. */
    fun frameFunction(name: String, arity: Int): IrSimpleFunctionSymbol =
        frame.owner.declarations.filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == name && it.parameters.size == arity + 1 }?.symbol
            ?: error("StoryFrame.$name with $arity arguments is missing from the story runtime")

    val enter: IrSimpleFunctionSymbol = member(frames, "enter")
    val put: IrSimpleFunctionSymbol = member(values, "put")
    val get: IrSimpleFunctionSymbol = member(values, "get")
    val keyOf: IrSimpleFunctionSymbol = member(instances, "keyOf")
    val loopCount: IrSimpleFunctionSymbol = member(loops, "count")
    val loopElement: IrSimpleFunctionSymbol = member(loops, "element")
    val loopSnapshot: IrSimpleFunctionSymbol = member(loops, "snapshot")

    /** The constructor of the stage lambda that takes [arity] parameters, or null when there is none so large. */
    fun stageLambdaConstructor(arity: Int): IrConstructorSymbol? =
        finder.findClass(ClassId(PACKAGE, Name.identifier("StageLambda$arity")))?.constructors?.single()

    fun isCheckpoint(function: IrFunction): Boolean = function.symbol in checkpoints

    /** Whether [function] is `action { }` or `label { }`, the body of an action. */
    fun isActionBody(function: IrFunction): Boolean = function.symbol in actionBodies

    private fun storyClass(name: String): IrClassSymbol =
        finder.findClass(ClassId(PACKAGE, Name.identifier(name))) ?: error("$name is missing from the story runtime")

    private fun storyFunction(name: String): IrSimpleFunctionSymbol =
        storyFunctions(name).firstOrNull() ?: error("$name is missing from the story runtime")

    private fun storyFunctions(name: String): Set<IrSimpleFunctionSymbol> =
        finder.findFunctions(CallableId(PACKAGE, Name.identifier(name))).toSet()

    private fun boxValue(): IrProperty = storyBox.owner.declarations.filterIsInstance<IrProperty>().single { it.name.asString() == "value" }

    private fun member(owner: IrClassSymbol, name: String): IrSimpleFunctionSymbol =
        owner.getSimpleFunction(name) ?: error("${owner.owner.name}.$name is missing from the story runtime")

    companion object {
        val PACKAGE = FqName("ru.hollowhorizon.hollowengine.addons.narrative.story")
        val STATE: FqName = PACKAGE.child(Name.identifier("State"))
        val ACTION: FqName = PACKAGE.child(Name.identifier("Action"))
        val UNSAVED: FqName = PACKAGE.child(Name.identifier("Unsaved"))

        fun find(context: IrPluginContext): StageSymbols? =
            context.finderForBuiltins().findClass(ClassId(PACKAGE, Name.identifier("State")))
                ?.let { StageSymbols(context, it) }
    }
}
