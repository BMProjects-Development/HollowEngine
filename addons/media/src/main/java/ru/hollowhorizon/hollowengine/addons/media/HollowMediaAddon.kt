package ru.hollowhorizon.hollowengine.addons.media

import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.media.api.HollowVideo
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegMedia
import ru.hollowhorizon.hollowengine.addons.media.decode.FFmpegSoundDecoder
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoder
import ru.hollowhorizon.hollowengine.api.VideoApi
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.addons.publish
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterCommandsEvent
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

class HollowMediaAddon : HollowAddonEntrypoint {
    private var video: HollowVideo? = null
    private var decoder: FFmpegSoundDecoder? = null

    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching(FFmpegMedia::preload).onFailure {
                HollowEngine.LOGGER.error("Failed to preload FFmpeg natives", it)
            }
        }
        video = HollowVideo(scope).also {
            context.hostServices.publish<VideoApi>(it)
        }
        decoder = FFmpegSoundDecoder().also { context.hostServices.publish<SoundDecoder>(it) }
    }

    override suspend fun unload(context: HollowAddonContext) {
        video?.close()
        video = null
        withContext(Dispatchers.IO) { decoder?.close() }
        decoder = null
    }

    @SubscribeEvent(-1)
    fun registerCommands(event: RegisterCommandsEvent) {
        val hollowEngineCommand = requireNotNull(
            event.dispatcher.root.getChild("hollowengine"),
        ) {
            "The HollowEngine root command must be registered before addon commands"
        }

        val videoCommand = Commands.literal("video")
            .then(
                Commands.argument(
                    "source",
                    StringArgumentType.string(),
                ).then(Commands.argument("player", EntityArgument.player()).executes { command ->
                    val activeVideo = video

                    if (activeVideo == null) {
                        command.source.sendFailure(
                            Component.literal(
                                "The HollowEngine media addon is not active.",
                            ),
                        )
                        return@executes 0
                    }

                    val player = EntityArgument.getPlayer(command, "player")
                    val source = StringArgumentType.getString(command, "source")

                    PlayVideoPacket(source).send(player)

                    1
                }),
            )

        hollowEngineCommand.addChild(videoCommand.build())
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class PlayVideoPacket(val source: String) : HollowAddonPacket {
    override fun handle(player: Player) {
        VideoApi.find()?.play(source)
    }
}
