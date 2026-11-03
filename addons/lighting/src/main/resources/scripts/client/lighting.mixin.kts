@file:ClientSide

import net.minecraft.client.Camera
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.qualet.irl.light.shadow.BlockShadowCache
import ru.hollowhorizon.hollowengine.addons.lighting.client.LightingClient

mixin<GameRenderer> {
    method("renderLevel") {
        before {
            LightingClient.beginFrame(args.get<DeltaTracker>(0).getGameTimeDeltaPartialTick(false))
        }
        afterCall<Camera>("setup") {
            LightingClient.uploadFrame()
        }
    }
}

mixin<ClientLevel> {
    method("sendBlockUpdated") {
        before {
            BlockShadowCache.invalidateChange(self, args.get<BlockPos>(0), args.get<BlockState>(1), args.get<BlockState>(2))
        }
    }
}

mixin<Minecraft> {
    method("close") {
        before { LightingClient.onClientClose() }
    }
    method("onResourceLoadFinished") {
        after { LightingClient.reloadResources() }
    }
}
