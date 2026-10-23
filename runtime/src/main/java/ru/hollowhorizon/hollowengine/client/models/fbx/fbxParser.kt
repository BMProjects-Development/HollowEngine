package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f

class Element(val keyToken: Token, parser: Parser) {
    val tokens = ArrayList<Token>()
    var compound: Scope? = null
        private set
    val scope get() = compound ?: parseError("expected compound scope", this)

    init {
        parser.advanceToNextToken()
        var needsValue = false
        while (true) {
            val next = parser.currentToken ?: break
            when (next.type) {
                TokenType.DATA -> {
                    val previous = tokens.lastOrNull()
                    if (!needsValue && previous != null && (next.isBinary || next.line == previous.line)) {
                        parseError("expected comma between values", next)
                    }
                    tokens += next
                    needsValue = false
                    parser.advanceToNextToken()
                }

                TokenType.COMMA -> {
                    if (needsValue || (tokens.isEmpty() && keyToken.stringContents != "Content")) parseError(
                        "unexpected comma",
                        next
                    )
                    needsValue = true
                    parser.advanceToNextToken()
                }

                TokenType.OPEN_BRACKET -> {
                    if (needsValue) parseError("missing value after comma", next)
                    compound = Scope(parser)
                    break
                }

                else -> break
            }
        }
        if (needsValue) parseError("missing value after comma", keyToken)
    }

    val peekPropertyName get() = tokens.firstOrNull()?.parseAsString.orEmpty()

    fun readTypedProperty(): Property? {
        if (tokens.size < 5) return null
        val value = tokens[4]
        return when (tokens[1].parseAsString) {
            "KString", "String", "Url", "DateTime" -> TypedProperty(value.parseAsString)
            "bool", "Bool" -> TypedProperty(value.parseAsInt != 0)
            "int", "Int", "enum", "Enum" -> TypedProperty(value.parseAsInt)
            "ULongLong", "KTime", "LongLong" -> TypedProperty(value.parseAsInt64)
            "Vector3D", "ColorRGB", "Vector", "Color", "Lcl Translation", "Lcl Rotation", "Lcl Scaling" -> TypedProperty(
                Vec3f(this[4].parseAsFloat, this[5].parseAsFloat, this[6].parseAsFloat)
            )

            "double", "Number", "Float", "float", "FieldOfView", "UnitScaleFactor" -> TypedProperty(value.parseAsFloat)
            else -> null
        }
    }

    operator fun get(index: Int) = tokens.getOrNull(index) ?: parseError("missing value at index $index", this)

    fun readMatrix(): Mat4f {
        val values = ArrayList<Float>()
        parseFloatsDataArray(values)
        if (values.size != 16) parseError("expected 16 matrix elements", this)
        return Mat4f.fromArray(values.toFloatArray())
    }

    fun parseFloatsDataArray(out: ArrayList<Float>, allowNonFinite: Boolean = false) {
        readArray(out, setOf('f', 'd'), { it.parseFloat(allowNonFinite) }) {
            if (it.type == 'd') it.data.double.toFloat() else it.data.float
        }
        if (!allowNonFinite && out.any { !it.isFinite() }) parseError("non-finite number in array", this)
    }

    fun parseIntsDataArray(out: ArrayList<Int>) = readArray(out, setOf('i', 'l', 'f', 'd'), Token::parseAsInt) {
        val value = it.readInteger()
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) parseError("array integer is out of range", this)
        value.toInt()
    }

    fun parseTimeDataArray(out: ArrayList<Long>) =
        readArray(out, setOf('l', 'i', 'f', 'd'), Token::parseAsInt64) { it.readInteger(round = true) }

    private fun <T> readArray(out: ArrayList<T>, types: Set<Char>, ascii: (Token) -> T, binary: (BinaryArray) -> T) {
        out.clear()
        val token = this[0]
        if (token.isBinary) {
            val array = readBinaryArray(token, types)
            repeat(array.count) { out += binary(array) }
        } else {
            val count = token.parseAsDim
            val values = compound?.get("a")?.tokens.orEmpty()
            if (values.size != count) parseError("array dimension $count does not match ${values.size} values", this)
            values.mapTo(out, ascii)
        }
    }

    inline fun <reified T> parseVectorDataArray(out: ArrayList<T>) {
        val values = ArrayList<Float>()
        parseFloatsDataArray(values)
        val size = when (T::class) {
            Vec2f::class -> 2
            Vec3f::class -> 3
            Vec4f::class -> 4
            else -> parseError("unsupported vector type", this)
        }
        if (values.size % size != 0) parseError("array length is not a multiple of $size", this)
        out.clear()
        for (i in values.indices step size) {
            val vector = when (size) {
                2 -> Vec2f(values[i], values[i + 1])
                3 -> Vec3f(values[i], values[i + 1], values[i + 2])
                else -> Vec4f(values[i], values[i + 1], values[i + 2], values[i + 3])
            }
            out += vector as T
        }
    }

    fun parseVec3DataArray(out: ArrayList<Vec3f>) = parseVectorDataArray(out)
}

class Scope(parser: Parser, topLevel: Boolean = false) {
    val elements = linkedMapOf<String, ArrayList<Element>>()

    init {
        if (!topLevel) {
            if (++parser.depth > 256) parseError("maximum nesting depth exceeded", parser.currentToken)
            parser.advanceToNextToken()
        }
        while (true) {
            val token = parser.currentToken
            if (token == null) {
                if (!topLevel) parseError("unclosed scope", parser.lastToken)
                break
            }
            if (token.type == TokenType.CLOSE_BRACKET) {
                if (topLevel) parseError("unexpected closing bracket", token)
                parser.advanceToNextToken()
                break
            }
            if (token.type != TokenType.KEY) parseError("expected element name", token)
            elements.getOrPut(token.stringContents, ::arrayListOf) += Element(token, parser)
        }
        if (!topLevel) parser.depth--
    }

    operator fun get(name: String) = elements[name]?.firstOrNull()
    fun getArray(name: String) = getCollection(name)
    fun getCollection(name: String) = elements[name] ?: arrayListOf()
}

class Parser(val tokens: ArrayList<Token>, val isBinary: Boolean) {
    private var cursor = 0
    var depth = 0
    var lastToken: Token? = null
        private set
    val currentToken get() = tokens.getOrNull(cursor)
    val root = Scope(this, true)

    fun advanceToNextToken(): Token? {
        lastToken = currentToken
        cursor++
        return currentToken
    }
}

fun parseError(message: String, element: Element? = null): Nothing = parseError(message, element?.keyToken)
fun parseError(message: String, token: Token?): Nothing =
    throw FbxImportException(token?.let { Util.addTokenText("FBX-Parser", message, it) } ?: "FBX-Parser: $message")
