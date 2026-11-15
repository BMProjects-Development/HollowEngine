package ru.hollowhorizon.hollowengine.common.commands

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonOperationResult
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonStatus
import ru.hollowhorizon.hollowengine.common.addons.displayName
import ru.hollowhorizon.hollowengine.common.utils.literal

internal fun CommandExtension.registerAddonCommands() {
    "addons" {
        "list" {
            executes {
                val statuses = HollowAddonManager.statuses
                val problems = HollowAddonManager.problems
                if (statuses.isEmpty() && problems.isEmpty()) {
                    return@executes sendSuccess(false) { "No addons installed.".literal }
                }
                statuses.forEach { status -> source.sendSuccess({ status.line().literal }, false) }
                problems.forEach { problem ->
                    source.sendSuccess({ "${problem.fileName}: unreadable - ${problem.message}".literal }, false)
                }
                statuses.size
            }
        }

        "info"(addonIdArgument()) {
            executes {
                val id = addonId()
                val copies = HollowAddonManager.statuses.filter { it.descriptor.id == id }
                if (copies.isEmpty()) return@executes sendFailure("Addon '$id' is not installed.".literal)
                copies.forEach { status ->
                    val descriptor = status.descriptor
                    val lines = listOf(
                        status.line(),
                        "  name: ${descriptor.name}",
                        "  platform: ${descriptor.platform.id}, environment: ${descriptor.environment.name.lowercase()}",
                        "  depends on addons: ${descriptor.addonRequirements.joinToString().ifEmpty { "-" }}",
                        "  depends on mods: ${descriptor.modRequirements.joinToString().ifEmpty { "-" }}",
                    )
                    lines.forEach { line -> source.sendSuccess({ line.literal }, false) }
                }
                copies.size
            }
        }

        "enable"(addonIdArgument()) {
            requires { hasPermission(2) }
            executes { report(HollowAddonManager.enable(addonId())) }
        }
        "disable"(addonIdArgument()) {
            requires { hasPermission(2) }
            executes { report(HollowAddonManager.disable(addonId())) }
        }
        "reload"(addonIdArgument()) {
            requires { hasPermission(2) }
            executes { report(HollowAddonManager.reload(addonId())) }
        }

    }
}

private fun HollowAddonStatus.line(): String =
    "${descriptor.id} ${descriptor.version}: ${state.displayName} [$fileName]" + details?.let { " - $it" }.orEmpty()

private fun addonIdArgument() = arg<String, CommandSourceStack>("id", StringArgumentType.word()) {
    HollowAddonManager.statuses.map { status -> status.descriptor.id }.distinct()
}

private fun com.mojang.brigadier.context.CommandContext<CommandSourceStack>.addonId(): String =
    StringArgumentType.getString(this, "id")

private fun com.mojang.brigadier.context.CommandContext<CommandSourceStack>.report(
    result: HollowAddonOperationResult,
): Int = if (result.successful) {
    sendSuccess(false) { result.message.literal }
} else {
    sendFailure(result.message.literal)
}
