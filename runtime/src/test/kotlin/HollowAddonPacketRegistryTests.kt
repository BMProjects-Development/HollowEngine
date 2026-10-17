package ru.hollowhorizon.hollowengine.network

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import kotlin.test.*

class HollowAddonPacketRegistryTests {
    @AfterTest
    fun clearRegistry() {
        HollowAddonPacketRegistry.unregister(ADDON_ID)
    }

    @Test
    fun `serializes registered addon packet through the shared payload`() {
        HollowAddonPacketRegistry.register(ADDON_ID, TestAddonPacket::class.java)

        val payload = HollowAddonPacketRegistry.encodeForClient(TestAddonPacket("https://example.com/video"))

        assertEquals(HollowAddonPacket.nameFor(ADDON_ID, TestAddonPacket::class.java), payload.key)
        assertEquals(TestAddonPacket("https://example.com/video"), HollowAddonPacketRegistry.decode(payload))
    }

    @Test
    fun `rejects packets sent in an unsupported direction`() {
        HollowAddonPacketRegistry.register(ADDON_ID, TestAddonPacket::class.java)

        assertFailsWith<IllegalArgumentException> {
            HollowAddonPacketRegistry.encodeForServer(TestAddonPacket("https://example.com/video"))
        }
    }

    @Test
    fun `unregister removes addon packet classes from the registry`() {
        HollowAddonPacketRegistry.register(ADDON_ID, TestAddonPacket::class.java)
        val payload = HollowAddonPacketRegistry.encodeForClient(TestAddonPacket("https://example.com/video"))

        HollowAddonPacketRegistry.unregister(ADDON_ID)

        assertNull(HollowAddonPacketRegistry.decode(payload))
        assertFailsWith<IllegalArgumentException> {
            HollowAddonPacketRegistry.encodeForClient(TestAddonPacket("https://example.com/video"))
        }
    }

    @Serializable
    @HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
    private data class TestAddonPacket(val source: String) : HollowAddonPacket {
        override fun handle(player: Player) = Unit
    }

    private companion object {
        const val ADDON_ID = "testaddon"
    }
}
