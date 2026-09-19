package ru.hollowhorizon.hollowengine.fabric

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.registry.HollowModProcessor
import ru.hollowhorizon.hollowengine.common.registry.getAnnotatedClasses
import ru.hollowhorizon.hollowengine.common.registry.getAnnotatedMethods
import ru.hollowhorizon.hollowengine.common.registry.getSubTypes
import ru.hollowhorizon.hollowengine.common.runtime.RuntimeAnnotationEnvironment
import ru.hollowhorizon.hollowengine.common.runtime.loadBootstrapOrRuntimeClass
import ru.hollowhorizon.hollowengine.common.runtime.resolve
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinScripts
import ru.hollowhorizon.hollowengine.common.scripting.startup.StartupScripts
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

object CoreInitialization {
    init {

        getSubTypes =
            { superType ->
                RuntimeAnnotationEnvironment.annotationIndex
                    .getSubTypes(superType.name, isPhysicalClient)
                    .mapNotNull { loadBootstrapOrRuntimeClass(it, HollowEngine::class.java.classLoader) }
                    .filter { isPhysicalClient || !it.isAnnotationPresent(ClientOnly::class.java) }
                    .toSet()
            }
        getAnnotatedClasses =
            { annotation ->
                RuntimeAnnotationEnvironment.annotationIndex
                    .getAnnotatedClasses(annotation.name, isPhysicalClient)
                    .mapNotNull { loadBootstrapOrRuntimeClass(it, HollowEngine::class.java.classLoader) }
                    .filter { isPhysicalClient || !it.isAnnotationPresent(ClientOnly::class.java) }
                    .toSet()
            }
        getAnnotatedMethods =
            { annotation ->
                RuntimeAnnotationEnvironment.annotationIndex
                    .getAnnotatedMethods(annotation.name, isPhysicalClient)
                    .mapNotNull { it.resolve(HollowEngine::class.java.classLoader) }
                    .filter { isPhysicalClient || !it.isAnnotationPresent(ClientOnly::class.java) }
                    .toSet()
            }

        HollowModProcessor
        HollowAddonManager.initializeAll()
        MixinScripts.run()
        StartupScripts.run()

        // Очищаем старые результаты сканирования, они в среднем жрут 500мб памяти, так что регистрация аннотаций должна быть одноразовой
        getSubTypes = { emptySet() }
        getAnnotatedClasses = { emptySet() }
        getAnnotatedMethods = { emptySet() }
    }
}
