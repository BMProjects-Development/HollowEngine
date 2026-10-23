package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.HollowEngine

fun domError(message: String, token: Token): Nothing = throw FbxImportException(Util.addTokenText("FBX-DOM", message, token))

fun domError(message: String, element: Element? = null): Nothing {
    element?.let { domError(message, element.keyToken) }
    throw FbxImportException("FBX-DOM $message")
}

fun domWarning(message: String, token: Token) = HollowEngine.LOGGER.warn(Util.addTokenText("FBX-DOM", message, token))

fun domWarning(message: String, element: Element? = null) {
    element?.let {
        domWarning(message, element.keyToken)
        return
    }
    HollowEngine.LOGGER.warn("FBX-DOM: $message")
}

/** fetch a property table and the corresponding property template */
fun getPropertyTable(doc: Document, templateName: String, element: Element, sc: Scope?, noWarn: Boolean = false): PropertyTable {

    val properties70 = sc?.get("Properties70")
    val templateProps = doc.templates[templateName].takeIf { templateName.isNotEmpty() }

    return if (properties70 == null) {
        if (!noWarn) domWarning("property table (Properties70) not found", element)
        templateProps ?: PropertyTable()
    } else PropertyTable(properties70, templateProps)
}

inline fun <reified T> processSimpleConnection(con: Connection, isObjectPropertyConn: Boolean, name: String, element: Element,
                                propNameOut: Array<String>? = null): T? {
    if (!isObjectPropertyConn && con.prop.isNotEmpty()) {
        domWarning("expected incoming $name link to be an object-object connection, ignoring", element)
        return null
    } else if (isObjectPropertyConn && con.prop.isEmpty()) {
        domWarning("expected incoming $name link to be an object-property connection, ignoring", element)
        return null
    }

    if (isObjectPropertyConn && propNameOut != null) propNameOut[0] = con.prop

    val ob = con.sourceObject
    if (ob == null) {
        domWarning("failed to read source object for incoming $name link, ignoring", element)
        return null
    }

    return ob as? T
}

fun getRequiredElement(scope: Scope, name: String, element: Element? = null): Element =
    scope[name] ?: parseError("missing required element $name", element)
