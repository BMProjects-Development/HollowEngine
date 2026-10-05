package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.allMaterials
import ru.hollowhorizon.hollowengine.client.models.internal.manager.MaterialSources
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.utils.Color
import java.util.IdentityHashMap

class ModelInstanceMaterials(model: Model) {
    private val instancesBySource = IdentityHashMap<Material, Material>()

    /** Materials a rig names that the model does not have, each made from the material of the mesh it was first given to. */
    private val added = LinkedHashMap<String, Pair<Material, Material>>()

    private val modelMaterials: List<Material>

    /** This instance's materials, the model's and those a rig added. */
    val values: List<Material> get() = modelMaterials + added.values.map { it.second }

    private val names: LinkedHashMap<String, Material>

    /** This instance's materials by name; a name the model uses twice keeps the first. */
    internal val byName: Map<String, Material> get() = names

    /** What this instance was last dressed in, to put back on when a source finally resolves. */
    private var wearing: Map<String, MaterialSource> = emptyMap()

    init {
        val sources = model.allMaterials()
        sources.forEach { source -> instancesBySource[source] = source.copyForInstance() }

        modelMaterials = sources.map { source -> instancesBySource.getValue(source) }
        names = modelMaterials
            .filter { it.name.isNotEmpty() }
            .associateByTo(LinkedHashMap(), Material::name)
    }

    fun resolve(source: Material): Material =
        instancesBySource[source] ?: source.copyForInstance().also { instancesBySource[source] = it }

    /**
     * The material called [name]: the model's own, or one made for it from [base], the material the mesh
     * asking for it was drawn with, which it looks like until it is dressed.
     */
    fun named(name: String, base: Material): Material = names[name] ?: run {
        val made = base.copyForInstance().also { it.name = name }
        added[name] = base to made
        names[name] = made
        made
    }

    /**
     * Dresses this instance in [overrides], and puts everything else back the way the model authored it.
     */
    fun apply(overrides: Map<String, MaterialSource>) {
        wearing = overrides
        instancesBySource.forEach { (source, instance) -> instance.restoreFrom(source) }
        added.values.forEach { (base, made) -> made.restoreFrom(base) }
        if (overrides.isEmpty()) return

        overrides.forEach { (name, source) ->
            val material = names[name] ?: return@forEach
            val resolved = MaterialSources.resolve(source) { apply(wearing) }
            resolved.texture?.let { material.texture = it }
            resolved.normal?.let { material.normalTexture = it }
            resolved.specular?.let { material.specularTexture = it }
            resolved.color?.let { hex -> Color.fromHexOrNull(hex)?.let { material.color = it } }
        }
    }

    private fun Material.restoreFrom(source: Material) {
        color = Color(source.color)
        texture = source.texture
        normalTexture = source.normalTexture
        specularTexture = source.specularTexture
        doubleSided = source.doubleSided
        blend = source.blend
    }

    private fun Material.copyForInstance(): Material = copy(color = Color(color))
}
