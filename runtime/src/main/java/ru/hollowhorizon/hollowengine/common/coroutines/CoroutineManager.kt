package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.*

internal val hollowCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun scopeAsync(block: suspend CoroutineScope.() -> Unit) = hollowCoroutineScope.async(block = block)