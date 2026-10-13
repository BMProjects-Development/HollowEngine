package ru.hollowhorizon.hollowengine.client.ui.widgets

/** Requests actions without blocking input. Deliver [publish] on the UI thread. */
fun interface UiIntentionActionsProvider {
    fun request(context: UiCompletionContext, publish: (List<UiTextQuickFix>) -> Unit)
}
