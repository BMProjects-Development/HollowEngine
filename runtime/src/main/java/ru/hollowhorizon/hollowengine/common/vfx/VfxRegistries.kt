package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxCollisionSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxForceSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxModuleSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxNoiseSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxUvAnimationSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxVelocityOverLifetimeSpec
import kotlin.reflect.KClass

/**
 * One kind of node an effect may contain: how it is stored, and what the editor calls it.
 */
class VfxNodeType<S : VfxNodeSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val icon: String? = null,
    val createDefault: (() -> S)? = null,
) {
    val key: ResourceLocation = id.rl
}

object VfxNodeTypes {
    val point = ExtensionPoints.create<VfxNodeType<*>>("hollowengine:vfx/node_types".rl)

    init {
        point.onChange(VfxModuleRevision::invalidate)

        builtIn("group", VfxGroupSpec.serializer(), "vfx/node_group.svg") { VfxGroupSpec() }
        builtIn("emitter", VfxEmitterSpec.serializer(), "vfx/node_emitter.svg") {
            VfxEmitterSpec(children = listOf(VfxPlaneSpec()))
        }
        builtIn("plane", VfxPlaneSpec.serializer(), "vfx/node_plane.svg") { VfxPlaneSpec() }
        builtIn("cube", VfxCubeSpec.serializer(), "vfx/node_cube.svg") { VfxCubeSpec() }
        builtIn("sphere", VfxSphereSpec.serializer(), "vfx/node_sphere.svg") { VfxSphereSpec() }
        builtIn("cylinder", VfxCylinderSpec.serializer(), "vfx/node_cylinder.svg") { VfxCylinderSpec() }
        builtIn("model", VfxModelSpec.serializer(), "vfx/node_model.svg") { VfxModelSpec() }
        builtIn("trail", VfxTrailSpec.serializer(), "vfx/node_trail.svg") { VfxTrailSpec() }
        builtIn("beam", VfxBeamSpec.serializer(), "vfx/node_beam.svg") { VfxBeamSpec() }
        builtIn("post_effect", VfxPostEffectSpec.serializer(), "vfx/node_post_effect.svg") { VfxPostEffectSpec() }
        builtIn("sky", VfxSkySpec.serializer(), "vfx/node_sky.svg") { VfxSkySpec() }
        builtIn("camera_shake", VfxCameraShakeSpec.serializer(), "vfx/node_camera_shake.svg") { VfxCameraShakeSpec() }
    }

    private inline fun <reified S : VfxNodeSpec> builtIn(
        name: String,
        serializer: KSerializer<S>,
        icon: String,
        noinline createDefault: () -> S,
    ) = register(
        VfxNodeType(
            id = "hollowengine:vfx/$name",
            specClass = S::class,
            serializer = serializer,
            titleKey = "hollowengine.gui.vfx.node_$name",
            icon = "hollowengine:textures/gui/icons/$icon",
            createDefault = createDefault,
        )
    )

    fun register(type: VfxNodeType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<VfxNodeType<*>> get() = point.extensions

    fun of(spec: VfxNodeSpec): VfxNodeType<*>? = all.firstOrNull { it.specClass.isInstance(spec) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(VfxNodeSpec::class) {
            all.forEach { type -> subclass(type) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<VfxNodeSpec>.subclass(type: VfxNodeType<*>) = subclass(
        type.specClass as KClass<VfxNodeSpec>,
        type.serializer as KSerializer<VfxNodeSpec>,
    )
}

/**
 * One kind of emitter module. How it is stored, what the editor calls it, and which nodes accept it.
 */
class VfxModuleType<S : VfxModuleSpec>(
    val id: String,
    val specClass: KClass<S>,
    val serializer: KSerializer<S>,
    val titleKey: String,
    val createDefault: (() -> S)? = null,
    val appliesTo: (VfxEmitterSpec) -> Boolean = { true },
    val repeatable: Boolean = false,
) {
    val key: ResourceLocation = id.rl
}

object VfxModuleTypes {
    val point = ExtensionPoints.create<VfxModuleType<*>>("hollowengine:vfx/module_types".rl)

    init {
        point.onChange(VfxModuleRevision::invalidate)

        register(
            VfxModuleType(
                id = "hollowengine:vfx/velocity_over_lifetime",
                specClass = VfxVelocityOverLifetimeSpec::class,
                serializer = VfxVelocityOverLifetimeSpec.serializer(),
                titleKey = "hollowengine.gui.vfx.module_velocity_over_lifetime",
                createDefault = { VfxVelocityOverLifetimeSpec() },
            )
        )
        register(
            VfxModuleType(
                id = "hollowengine:vfx/force",
                specClass = VfxForceSpec::class,
                serializer = VfxForceSpec.serializer(),
                titleKey = "hollowengine.gui.vfx.module_force",
                createDefault = { VfxForceSpec() },
                repeatable = true,
            )
        )
        register(
            VfxModuleType(
                id = "hollowengine:vfx/noise",
                specClass = VfxNoiseSpec::class,
                serializer = VfxNoiseSpec.serializer(),
                titleKey = "hollowengine.gui.vfx.module_noise",
                createDefault = { VfxNoiseSpec() },
            )
        )
        register(
            VfxModuleType(
                id = "hollowengine:vfx/collision",
                specClass = VfxCollisionSpec::class,
                serializer = VfxCollisionSpec.serializer(),
                titleKey = "hollowengine.gui.vfx.module_collision",
                createDefault = { VfxCollisionSpec() },
            )
        )
        register(
            VfxModuleType(
                id = "hollowengine:vfx/uv_animation",
                specClass = VfxUvAnimationSpec::class,
                serializer = VfxUvAnimationSpec.serializer(),
                titleKey = "hollowengine.gui.vfx.module_uv_animation",
                createDefault = { VfxUvAnimationSpec() },
            )
        )
    }

    fun register(type: VfxModuleType<*>): ExtensionHandle = point.register(type.key, type)

    val all: List<VfxModuleType<*>> get() = point.extensions

    fun of(spec: VfxModuleSpec): VfxModuleType<*>? = all.firstOrNull { it.specClass.isInstance(spec) }

    fun forEmitter(emitter: VfxEmitterSpec): List<VfxModuleType<*>> = all.filter { it.appliesTo(emitter) }

    fun registerInto(builder: SerializersModuleBuilder) {
        builder.polymorphic(VfxModuleSpec::class) {
            all.forEach { type -> subclass(type) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PolymorphicModuleBuilder<VfxModuleSpec>.subclass(type: VfxModuleType<*>) = subclass(
        type.specClass as KClass<VfxModuleSpec>,
        type.serializer as KSerializer<VfxModuleSpec>,
    )
}

/**
 * Says when the serializers module of [VfxFormat] has to be built again, which is whenever an addon
 * registers a node or module kind.
 */
object VfxModuleRevision {
    @Volatile
    var current: Int = 0
        private set

    @Synchronized
    fun invalidate() {
        current++
        TagModuleRevision.invalidate()
    }
}
