package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.*

class HollowAddonExtensionsTests {
    @Test
    fun `cleanup removes owned extensions in reverse order`() {
        val point = point()
        val scope = OwnedHollowAddonExtensions("demo", javaClass.classLoader)
        val cleanupOrder = mutableListOf<String>()

        scope.register(point, "first", TestExtension("first"))
        scope.onUnload { cleanupOrder += "first-cleanup" }
        scope.register(point, "second", TestExtension("second"))
        scope.onUnload { cleanupOrder += "second-cleanup" }

        assertEquals(listOf("first", "second"), point.extensions.map(TestExtension::name))
        scope.cleanup()

        assertTrue(point.extensions.isEmpty())
        assertEquals(listOf("second-cleanup", "first-cleanup"), cleanupOrder)
    }

    @Test
    fun `qualified identities isolate owners while rejecting duplicate owner keys`() {
        val point = point()
        val first = OwnedHollowAddonExtensions("first-addon", javaClass.classLoader)
        val second = OwnedHollowAddonExtensions("second-addon", javaClass.classLoader)

        first.register(point, "editor", TestExtension("first"))
        second.register(point, "editor", TestExtension("second"))

        assertEquals(
            listOf("first-addon:editor", "second-addon:editor"),
            point.registrations.map { it.qualifiedId },
        )
        assertFailsWith<IllegalArgumentException> {
            first.register(point, "editor", TestExtension("duplicate"))
        }

        first.cleanup()
        second.cleanup()
    }

    @Test
    fun `priority wins before deterministic registration order`() {
        val point = point()
        val scope = OwnedHollowAddonExtensions("demo", javaClass.classLoader)

        scope.register(point, "normal", TestExtension("normal"), priority = 0)
        scope.register(point, "first-high", TestExtension("first-high"), priority = 10)
        scope.register(point, "second-high", TestExtension("second-high"), priority = 10)

        assertEquals(listOf("first-high", "second-high", "normal"), point.extensions.map(TestExtension::name))
        scope.cleanup()
    }

    @Test
    fun `manual registration close is idempotent`() {
        val point = point()
        val scope = OwnedHollowAddonExtensions("demo", javaClass.classLoader)
        val registration = scope.register(point, "editor", TestExtension("editor"))

        registration.close()
        registration.close()

        assertFalse(registration.isActive)
        assertTrue(point.extensions.isEmpty())
        scope.cleanup()
    }

    @Test
    fun `closed scope rejects and rolls back late registrations`() {
        val point = point()
        val scope = OwnedHollowAddonExtensions("demo", javaClass.classLoader)
        scope.cleanup()

        assertFailsWith<IllegalStateException> {
            scope.register(point, "late", TestExtension("late"))
        }
        assertTrue(point.extensions.isEmpty())
    }

    @Test
    fun `extension callbacks use their defining context classloader`() {
        val point = point()
        val loader = object : ClassLoader(javaClass.classLoader) {}
        val scope = OwnedHollowAddonExtensions("demo", loader)
        scope.register(point, "editor", TestExtension("editor"))

        val observed = point.registrations.single().invoke { Thread.currentThread().contextClassLoader }

        assertSame(loader, observed)
        scope.cleanup()
    }

    @Test
    fun `engine and addon registrations share one extension point`() {
        val id = "test:${java.util.UUID.randomUUID()}".rl
        val point = ExtensionPoints.create(id, TestExtension::class)
        val samePoint = ExtensionPoints.create(id, TestExtension::class)
        val changes = mutableListOf<HollowAddonExtensionChange<TestExtension>>()
        val observer = point.observe(changes::add)
        val engineHandle = point.register("test:engine".rl, TestExtension("engine"))
        val addon = OwnedHollowAddonExtensions("demo", javaClass.classLoader)
        addon.register(samePoint, "extension", TestExtension("addon"), priority = 1)

        assertSame(point, samePoint)
        assertEquals(listOf("addon", "engine"), point.extensions.map(TestExtension::name))
        addon.cleanup()
        engineHandle.close()
        observer.close()
        assertEquals(4, changes.size)
        assertTrue(point.extensions.isEmpty())
    }

    private data class TestExtension(val name: String)

    private fun point() = ExtensionPoints.create("test:${java.util.UUID.randomUUID()}".rl, TestExtension::class)
}
