package ru.hollowhorizon.hollowengine.common.config

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.TomlTable
import ru.hollowhorizon.hollowengine.common.config.properties.ConfigProperty
import ru.hollowhorizon.hollowengine.common.config.properties.Properties
import ru.hollowhorizon.hollowengine.common.utils.ObservableList
import ru.hollowhorizon.hollowengine.common.utils.ObservableMap
import ru.hollowhorizon.hollowengine.common.utils.ObservableSet
import ru.hollowhorizon.hollowengine.common.utils.toml.toml
import kotlin.test.*

class ConfigPropertyTests {

    class TestHost {
        val noAnnotation by ConfigProperty("hello", {})
    }

    @Test
    fun `set updates value and triggers onChange`() {
        var changed = false
        val host = object {
            var x by ConfigProperty(0, { changed = true })
        }
        host.x = 100
        assertEquals(100, host.x)
        assertTrue(changed)
    }

    @Test
    fun `deserialize with invalid value keeps default`() {
        val host = TestHost()
        val prop = ConfigProperty(5.0f, {})
        prop.provideDelegate(host, TestHost::noAnnotation)
        prop.name = "ranged"
        prop.range = 0f..10f

        val invalidToml = toml.encodeToTomlElement(serializer<Float>(), 999.0f)
        val result = prop.deserialize(invalidToml)
        assertFalse(result, "deserialize should return false for invalid value")
        assertEquals(5.0f, prop.getValue(host, TestHost::noAnnotation))
    }

    @Test
    fun `deserialize with invalid validValues keeps default`() {
        val host = TestHost()
        val prop = ConfigProperty("medium", {})
        prop.provideDelegate(host, TestHost::noAnnotation)
        prop.name = "quality"
        prop.validValues = setOf("low", "medium", "high")

        val invalidToml = toml.encodeToTomlElement(serializer<String>(), "extreme")
        val result = prop.deserialize(invalidToml)
        assertFalse(result, "deserialize should return false for invalid validValues")
        assertEquals("medium", prop.getValue(host, TestHost::noAnnotation))
    }

    @Test
    fun `serialize then deserialize roundtrips`() {
        val host = TestHost()
        val prop = ConfigProperty("hello", {})
        prop.provideDelegate(host, TestHost::noAnnotation)
        prop.name = "testKey"

        val element = prop.serialize()
        assertNotNull(element)

        val prop2 = ConfigProperty("default", {})
        prop2.provideDelegate(host, TestHost::noAnnotation)
        prop2.name = "testKey"
        prop2.deserialize(element)
        assertEquals("hello", prop2.getValue(host, TestHost::noAnnotation))
    }

    @Test
    fun `deserialize preserves observable list`() {
        var changes = 0
        val property = ConfigProperty(ObservableList(mutableListOf("default"), { changes++ }, serializer<String>()), {})
        val element = toml.encodeToTomlElement(ListSerializer(serializer<String>()), listOf("loaded"))

        assertTrue(property.deserialize(element))
        assertEquals(listOf("loaded"), property.getValue(null, TestHost::noAnnotation))
        assertEquals(0, changes)

        property.getValue(null, TestHost::noAnnotation).add("added")
        assertEquals(1, changes)
    }

    @Test
    fun `deserialize preserves observable set`() {
        var changes = 0
        val property = ConfigProperty(ObservableSet(mutableSetOf("default"), { changes++ }, serializer<String>()), {})
        val element = toml.encodeToTomlElement(ListSerializer(serializer<String>()), listOf("loaded"))

        assertTrue(property.deserialize(element))
        assertEquals(setOf("loaded"), property.getValue(null, TestHost::noAnnotation))
        assertEquals(0, changes)

        property.getValue(null, TestHost::noAnnotation).add("added")
        assertEquals(1, changes)
    }

    @Test
    fun `deserialize preserves observable map`() {
        var changes = 0
        val property = ConfigProperty(
            ObservableMap(
                mutableMapOf("default" to 0),
                { changes++ },
                serializer<String>(),
                serializer<Int>()
            ), {})
        val element = toml.encodeToTomlElement(
            MapSerializer(serializer<String>(), serializer<Int>()), mapOf("loaded" to 1)
        )

        assertTrue(property.deserialize(element))
        assertEquals(mapOf("loaded" to 1), property.getValue(null, TestHost::noAnnotation))
        assertEquals(0, changes)

        property.getValue(null, TestHost::noAnnotation)["added"] = 2
        assertEquals(1, changes)
    }
}

class PropertiesTests {

    class TestHost {
        var knownKey by ConfigProperty("default", {})
    }

    @Test
    fun `deserialize populates property`() {
        var changeCount = 0
        val props = Properties { changeCount++ }
        val host = TestHost()

        val prop = props.add("default")
        prop.provideDelegate(host, TestHost::knownKey)
        prop.name = "knownKey"

        val table = TomlTable(
            "knownKey" to toml.encodeToTomlElement(serializer<String>(), "loadedValue")
        )
        props.deserialize(table)
        assertEquals("loadedValue", prop.getValue(host, TestHost::knownKey))
    }

    @Test
    fun `orphaned keys are preserved in serialize`() {
        val props = Properties {}
        val host = TestHost()

        val prop = props.add("myValue")
        prop.provideDelegate(host, TestHost::knownKey)
        prop.name = "knownKey"

        val table = TomlTable(
            "knownKey" to toml.encodeToTomlElement(serializer<String>(), "hello"),
            "oldKey" to toml.encodeToTomlElement(serializer<Int>(), 42),
            "removedSetting" to toml.encodeToTomlElement(serializer<Boolean>(), true)
        )
        props.deserialize(table)
        assertTrue(props.hasOrphanedKeys(), "should have orphaned keys after deserializing extra keys")

        val serialized = props.serialize()
        assertNotNull(serialized["oldKey"], "orphaned key 'oldKey' should be preserved")
        assertNotNull(serialized["removedSetting"], "orphaned key 'removedSetting' should be preserved")
        assertNotNull(serialized["knownKey"], "known key should still be present")
    }

    @Test
    fun `clearOrphanedKeys removes orphans on next serialize`() {
        val props = Properties {}
        val host = TestHost()

        val prop = props.add("myValue")
        prop.provideDelegate(host, TestHost::knownKey)
        prop.name = "knownKey"

        val table = TomlTable(
            "knownKey" to toml.encodeToTomlElement(serializer<String>(), "hello"),
            "oldKey" to toml.encodeToTomlElement(serializer<Int>(), 42)
        )
        props.deserialize(table)
        assertTrue(props.hasOrphanedKeys())

        props.clearOrphanedKeys()
        assertFalse(props.hasOrphanedKeys(), "should have no orphaned keys after clearing")

        val serialized = props.serialize()
        assertNotNull(serialized["knownKey"])
        assertNull(serialized["oldKey"], "orphaned key should be removed after clearing")
    }

    @Test
    fun `deserialize with missing keys leaves defaults`() {
        val props = Properties {}
        val host = TestHost()

        val prop = props.add("defaultValue")
        prop.provideDelegate(host, TestHost::knownKey)
        prop.name = "knownKey"

        val table = TomlTable()
        props.deserialize(table)
        assertEquals("defaultValue", prop.getValue(host, TestHost::knownKey))
    }
}

class ConfigCollectionTests {

    private class TestConfig : Config() {
        var listValues: List<String> by list("default")
        var setValues: Set<String> by set("default")
        var mapValues: Map<String, Int> by map("default" to 0)
    }

    @Test
    fun `loading observable collections keeps delegated property types`() {
        val config = TestConfig()
        val table = TomlTable(
            "listValues" to toml.encodeToTomlElement(ListSerializer(serializer<String>()), listOf("list")),
            "setValues" to toml.encodeToTomlElement(ListSerializer(serializer<String>()), listOf("set")),
            "mapValues" to toml.encodeToTomlElement(
                MapSerializer(serializer<String>(), serializer<Int>()), mapOf("map" to 1)
            )
        )

        config.properties.deserialize(table)

        assertEquals(listOf("list"), config.listValues)
        assertEquals(setOf("set"), config.setValues)
        assertEquals(mapOf("map" to 1), config.mapValues)
    }
}
