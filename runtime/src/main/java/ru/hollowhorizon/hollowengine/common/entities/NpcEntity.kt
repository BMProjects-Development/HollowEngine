package ru.hollowhorizon.hollowengine.common.entities

import com.mojang.authlib.GameProfile
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.snapshotOf
import ru.hollowhorizon.hollowengine.common.npcs.actions.NpcActionController
import ru.hollowhorizon.hollowengine.common.npcs.inventory.NpcInventory
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NpcMoveControl
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NpcPathNavigation
import ru.hollowhorizon.hollowengine.common.npcs.navigation.maxDrop
import ru.hollowhorizon.hollowengine.common.registry.ModEntities
import ru.hollowhorizon.hollowengine.common.utils.FakePlayer
import ru.hollowhorizon.hollowengine.common.utils.mutableAttributes
import java.util.UUID

class NpcEntity : PathfinderMob {
    constructor(level: Level) : super(ModEntities.NPC_ENTITY, level)
    constructor(type: EntityType<NpcEntity>, world: Level) : super(type, world)

    init {
        moveControl = NpcMoveControl(this)
    }

    val goals get() = goalSelector

    val actions by lazy { NpcActionController(coroutineScope) }
    val inventory = NpcInventory()

    private var cachedFakePlayer: ServerPlayer? = null

    val fakePlayer: ServerPlayer
        get() {
            val level = level() as ServerLevel
            return cachedFakePlayer?.takeIf { it.level() === level } ?: FakePlayer.create(
                level,
                GameProfile(fakePlayerUuid(), "npc_${uuid.toString().take(12)}"),
            ).also { player ->
                player.setGameMode(GameType.SURVIVAL)
                cachedFakePlayer = player
            }
        }

    var maxDeathTime: Int = 20

    private fun fakePlayerUuid(): UUID = UUID.nameUUIDFromBytes("hollowengine:npc:$uuid".encodeToByteArray())

    init {
        setCanPickUpLoot(true)
    }


    override fun createNavigation(pLevel: Level) = NpcPathNavigation(pLevel, this)

    val npcNavigation: NpcPathNavigation get() = navigation as NpcPathNavigation

    /** How far it steps off a ledge, by its navigation settings rather than by its health as mobs do. */
    override fun getMaxFallDistance(): Int = npcNavigation.settings.path.maxDrop(this)

    override fun registerGoals() {
        goalSelector.addGoal(1, FloatGoal(this))
        goalSelector.addGoal(1, MeleeAttackGoal(this, 1.0, false))
    }

    override fun shouldDespawnInPeaceful() = false
    override fun canPickUpLoot() = true
    override fun wantsToPickUp(pStack: ItemStack) = false


    override fun aiStep() {
        updateSwingTime()
        super.aiStep()
    }

    override fun removeWhenFarAway(dist: Double) = false
    override fun isPersistenceRequired() = true

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.put(INVENTORY_KEY, CompoundTag().also { inventory.save(it, registryAccess()) })
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        if (compound.contains(INVENTORY_KEY)) {
            inventory.load(compound.getCompound(INVENTORY_KEY), registryAccess())
        }
    }

    override fun tickDeath() {
        ++this.deathTime;

        val c0 = this.deathTime >= this.maxDeathTime
        val c1 = !this.level().isClientSide()
        val c2 = !this.isRemoved
        if (c0 && c1 && c2) {
            this.level().broadcastEntityEvent(this, 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    val pickupDistance get() = pickupReach

    fun seat() {
        SeatEntity.seat(this)
    }

    fun standup() {
        vehicle?.let { stopRiding() }
    }

    fun clearTarget() {
        target = null
    }

    fun setAttributes(attributes: Map<String, Float>) {
        attributes.forEach { (attributeName, value) ->
            if (!value.isFinite()) return@forEach
            val location = ResourceLocation.tryParse(attributeName) ?: return@forEach
            val attribute = BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null) ?: return@forEach
            this.attributes.mutableAttributes.getOrCreate(attribute).baseValue = attribute.value().sanitizeValue(value.toDouble())
        }
    }


    companion object {
        private const val INVENTORY_KEY = "NpcInventory"

        fun createAttributes(): AttributeSupplier.Builder {
            return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.ATTACK_SPEED, 4.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0)
                .add(Attributes.FOLLOW_RANGE, 128.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0)
                .add(Attributes.FLYING_SPEED, 0.4)
                .add(Attributes.LUCK, 0.0)
        }

    }
}
