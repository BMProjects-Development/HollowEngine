package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.diagnostic.Logger

object EmptyLogger: Logger() {
    override fun isDebugEnabled() = false
    override fun debug(p0: String?, p1: Throwable?) {}
    override fun info(p0: String?, p1: Throwable?) {}
    override fun warn(p0: String?, p1: Throwable?) {}
    override fun error(p0: String?, p1: Throwable?, vararg p2: String) {}
}
