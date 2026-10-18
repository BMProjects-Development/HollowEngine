import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.npcs.navigation.AvoidRules
import ru.hollowhorizon.hollowengine.common.npcs.navigation.AvoidSettings
import ru.hollowhorizon.hollowengine.common.npcs.navigation.Zone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcAvoidTests {
    /** Zones alone never look at blocks. */
    private object NoBlocks : BlockGetter {
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = error("zones do not read blocks")
        override fun getBlockState(pos: BlockPos): BlockState = error("zones do not read blocks")
        override fun getFluidState(pos: BlockPos): FluidState = error("zones do not read blocks")
        override fun getHeight(): Int = 384
        override fun getMinBuildHeight(): Int = -64
    }

    private fun rules(vararg zones: Zone, start: BlockPos = BlockPos(0, 64, 0)) =
        AvoidRules(NoBlocks, AvoidSettings(), zones.toList(), bodyHeight = 2, start = start, startsInWater = false)

    private val wall = Zone.box(Vec3(2.0, 60.0, -1.0), Vec3(3.0, 70.0, 2.0))

    @Test
    fun `a zone forbids the blocks whose middles it holds and costs where it is soft`() {
        val rules = rules(wall, Zone.sphere(Vec3(10.5, 64.5, 0.5), 1.0, cost = 5f))
        assertEquals(AvoidRules.FORBIDDEN, rules.cost(2, 64, 0))
        assertEquals(0f, rules.cost(1, 64, 0))
        assertEquals(5f, rules.cost(10, 64, 0))
    }

    @Test
    fun `an npc standing in a forbidden zone may walk out of it`() {
        val rules = rules(wall, start = BlockPos(2, 64, 0))
        assertEquals(AvoidRules.ESCAPE_COST, rules.cost(2, 64, 1))
    }

    @Test
    fun `a straight walk does not cut across a zone but may start and end at its edge`() {
        val rules = rules(wall)
        assertTrue(rules.crosses(Vec3(0.5, 64.0, 0.5), Vec3(4.5, 64.0, 0.5)))
        assertFalse(rules.crosses(Vec3(0.5, 64.0, 0.5), Vec3(1.5, 64.0, 3.5)))
        assertFalse(rules.crosses(Vec3(2.5, 64.0, 0.5), Vec3(2.5, 64.0, 0.6)), "start and end cells are the path's own")
    }
}
