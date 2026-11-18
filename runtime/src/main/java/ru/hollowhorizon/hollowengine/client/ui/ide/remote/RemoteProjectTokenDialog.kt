package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.ui.ide.Actions
import ru.hollowhorizon.hollowengine.client.ui.ide.DialogButton
import ru.hollowhorizon.hollowengine.client.ui.ide.Paragraph
import ru.hollowhorizon.hollowengine.client.ui.ide.ProjectDialog
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectAccess
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectReply
import ru.hollowhorizon.hollowengine.common.addons.project.remote.isRemoteProjectToken

@Composable
internal fun RemoteProjectTokenDialog(onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val access = RemoteProjectConnection.access
    val session = RemoteProjectConnection.session
    LaunchedEffect(access, session) {
        when (access) {
            RemoteProjectAccess.GRANTED -> onUnlocked()
            RemoteProjectAccess.UNAVAILABLE -> onDismiss()
            RemoteProjectAccess.LOCKED -> Unit
        }
    }
    if (access != RemoteProjectAccess.LOCKED) return
    var submitting by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    ProjectDialog("remote-project-token", "$RemoteLang.unlock".lang, onDismiss) {
        Paragraph("$RemoteLang.token_prompt".lang)
        error?.let { Paragraph(it, "project-dialog-error") }
        Actions {
            DialogButton("remote-project-token-cancel", "$RemoteLang.cancel".lang, onClick = onDismiss)
            DialogButton(
                "remote-project-token-submit",
                "$RemoteLang.${if (submitting) "authenticating" else "paste_token"}".lang,
                primary = true,
                enabled = !submitting,
            ) {
                val token = Minecraft.getInstance().keyboardHandler.clipboard.trim()
                if (!token.isRemoteProjectToken()) {
                    error = "$RemoteLang.invalid_token".lang
                    return@DialogButton
                }
                error = null
                submitting = true
                RemoteProjectConnection.authenticate(token) { reply ->
                    if (reply is RemoteProjectReply.Failure) {
                        submitting = false
                        error = reply.error.message()
                    }
                }
            }
        }
    }
}
