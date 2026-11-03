package org.qualet.irl.light.shadow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import ru.hollowhorizon.hollowengine.client.render.RenderManager;

/** Real entity geometry, including HollowEngine world objects, through the normal renderer. */
public final class HollowShadowCasters implements ShadowCasterSource {
    @Override
    public void collect(ClientLevel world, Vec3 camera, float partialTick, OccluderSink sink) {
        for (Entity entity : world.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) continue;
            Vec3 position = entity.getPosition(partialTick);
            // The baker performs per-light sphere/cone selection. A distant large light may still reach this entity.
            AABB bounds = RenderManager.INSTANCE.extendCullingBounds(entity, entity.getBoundingBoxForCulling())
                .move(position.subtract(entity.position()));
            sink.emitFromBox(entity, CasterType.ENTITY, false,
                (bounds.minX + bounds.maxX) * 0.5, bounds.minY, (bounds.minZ + bounds.maxZ) * 0.5,
                bounds, 1f, 0L);
        }
    }

    @Override
    public void emitOccluder(Object caster, int type, float partialTick, OccluderBatch batch) {
        Entity entity = (Entity) caster;
        ImmediateOccluderBatch target = (ImmediateOccluderBatch) batch;
        Vec3 position = entity.getPosition(partialTick);
        Minecraft.getInstance().getEntityRenderDispatcher().render(entity,
            position.x - ShadowRenderer.currentOriginX(), position.y - ShadowRenderer.currentOriginY(),
            position.z - ShadowRenderer.currentOriginZ(), entity.getViewYRot(partialTick), partialTick,
            target.matrices(), target.immediate(), LightTexture.FULL_BRIGHT);
    }
}
