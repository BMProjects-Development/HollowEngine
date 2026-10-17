package ru.hollowhorizon.hollowengine.common.entities

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.*
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.registry.ModEntities


class SeatEntity(entityType: EntityType<SeatEntity>, pLevel: Level) : LivingEntity(entityType, pLevel) {
    constructor(level: Level) : this(ModEntities.SEAT, level)

    constructor(level: Level, pos: Vec3, yaw: Float) : this(level) {
        this.setPos(pos.x, pos.y, pos.z)
        this.setRot(yaw, 0F)
    }

    init {
        this.noPhysics = true
        this.isNoGravity = true
        this.isInvulnerable = true
    }

    override fun tick() {
        super.tick()
        if (this.level().isClientSide) return

        // The seat only exists to carry its rider, so it goes away as soon as the rider leaves.
        if (this.passengers.isEmpty()) this.discard()
    }

    override fun isPushable() = false

    override fun getMainArm(): HumanoidArm = HumanoidArm.RIGHT

    override fun getAddEntityPacket(entity: ServerEntity): Packet<ClientGamePacketListener?> {
        return ClientboundAddEntityPacket(this, entity)
    }

    override fun readAdditionalSaveData(p0: CompoundTag) {}
    override fun getArmorSlots(): Iterable<ItemStack> = emptySet()

    override fun getItemBySlot(slot: EquipmentSlot)= ItemStack.EMPTY

    override fun setItemSlot(
        slot: EquipmentSlot,
        stack: ItemStack,
    ) {
    }

    override fun addAdditionalSaveData(p0: CompoundTag) {}


    override fun canRide(pVehicle: Entity): Boolean = true

    override fun getDismountLocationForPassenger(entity: LivingEntity): Vec3 {
        val original = this.direction
        val offsets = arrayOf(original, original.clockWise, original.counterClockWise, original.opposite)
        for (dir in offsets) {
            val safeVec = DismountHelper.findSafeDismountLocation(
                entity.type, this.level(),
                blockPosition().relative(dir), false
            )
            if (safeVec != null) {
                return safeVec.add(0.0, 0.25, 0.0)
            }
        }
        return super.getDismountLocationForPassenger(entity)
    }

    override fun addPassenger(entity: Entity) {
        super.addPassenger(entity)
        entity.yRot = yRot
    }

    override fun positionRider(passenger: Entity, callback: MoveFunction) {
        super.positionRider(passenger, callback)
        this.yaw(passenger)
    }

    override fun onPassengerTurned(entity: Entity) {
        this.yaw(entity)
    }

    private fun yaw(passenger: Entity) {
        passenger.setYBodyRot(this.yRot)
        val wrappedYaw = Mth.wrapDegrees(passenger.yRot - this.yRot)
        val clampedYaw = Mth.clamp(wrappedYaw, -120.0f, 120.0f)
        passenger.yRotO += clampedYaw - wrappedYaw
        passenger.yRot = passenger.yRot + clampedYaw - wrappedYaw
        passenger.yHeadRot = passenger.yRot
    }

    companion object {
        fun seat(entity: Entity) {
            val level = entity.level()

            if (level.isClientSide()) return

            val seat = SeatEntity(level, entity.position(), entity.yRot)
            level.addFreshEntity(seat)
            entity.startRiding(seat, false)
        }
    }
}
