package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.client.models.util.Buffer
import ru.hollowhorizon.hollowengine.client.models.util.rem
import ru.hollowhorizon.hollowengine.client.models.util.set
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f
import java.nio.ByteBuffer
import java.util.zip.Inflater
import kotlin.reflect.KMutableProperty0
import ru.hollowhorizon.hollowengine.client.models.fbx.TokenType as Tt


class Element(val keyToken: Token, parser: Parser) {

    val tokens = ArrayList<Token>()
    var compound: Scope? = null

    var begin = 0
    var type = ' '
    var count = 0

    init {
        lateinit var n: Token
        do {
            n = parser.advanceToNextToken() ?: parseError("unexpected end of file, expected closing bracket", parser.lastToken)
            if (n.type == Tt.DATA) {
                tokens.add(n)
                val prev = n
                n = parser.advanceToNextToken() ?: parseError("unexpected end of file, expected bracket, comma or key", parser.lastToken)
                val ty = n.type
                // some exporters are missing a comma on the next line
                if (ty == Tt.DATA && prev.type == Tt.DATA && n.line == prev.line + 1) {
                    tokens.add(n)
                    continue
                }
                if (ty != Tt.OPEN_BRACKET && ty != Tt.CLOSE_BRACKET && ty != Tt.COMMA && ty != Tt.KEY)
                    parseError("unexpected token; expected bracket, comma or key", n)
            }
            if (n.type == Tt.OPEN_BRACKET) {
                compound = Scope(parser)

                // current token should be a TOK_CLOSE_BRACKET
                n = parser.currentToken!!

                if (n.type != Tt.CLOSE_BRACKET) parseError("expected closing bracket", n)

                parser.advanceToNextToken()
                break
            }
        } while (n.type != Tt.KEY && n.type != Tt.CLOSE_BRACKET)
    }

    val scope get() = compound ?: parseError("expected compound scope", this)

    /** peek into an element and check if it contains a FBX property, if so return its name.    */
    val peekPropertyName: String
        get() {
            assert(keyToken.stringContents == "P")
            return if (tokens.size < 4) "" else tokens[0].parseAsString
        }

    /** read a typed property out of a FBX element. The return value is NULL if the property cannot be read. */
    fun readTypedProperty(): Property? {
        assert(keyToken.stringContents == "P")

        assert(tokens.size >= 5)
        val t = tokens[4]
        return when (tokens[1].parseAsString) {
            "KString" -> TypedProperty(t.parseAsString)
            "bool", "Bool" -> TypedProperty(t.parseAsInt != 0)
            "int", "Int", "enum", "Enum" -> TypedProperty(t.parseAsInt)
            "ULongLong" -> TypedProperty(t.parseAsId)
            "KTime" -> TypedProperty(t.parseAsInt64)
            "Vector3D", "ColorRGB", "Vector", "Color", "Lcl Translation", "Lcl Rotation", "Lcl Scaling" ->
                TypedProperty(Vec3f(tokens[4 + 0].parseAsFloat, tokens[4 + 1].parseAsFloat, tokens[4 + 2].parseAsFloat))
            "double", "Number", "Float", "FieldOfView", "UnitScaleFactor" -> TypedProperty(t.parseAsFloat)
            else -> null
        }
    }

    /** get token at a particular index */
    operator fun get(index: Int) = tokens.getOrNull(index) ?: parseError("missing token at index $index", this)

    /** read a 4x4 matrix from an array of 16 floats */
    fun readMatrix(): Mat4f {
        val values = ArrayList<Float>()
        parseFloatsDataArray(values)

        if (values.size != 16) parseError("expected 16 matrix elements")

        return Mat4f.fromArray(values.toFloatArray())
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T> parseVectorDataArray(out: ArrayList<T>) = when (T::class) {
        Vec2f::class -> parseVec2DataArray(out as ArrayList<Vec2f>)
        Vec3f::class -> parseVec3DataArray(out as ArrayList<Vec3f>)
        Vec4f::class -> parseVec4DataArray(out as ArrayList<Vec4f>)
        else              -> throw Exception("Got reified type ${T::class.java.simpleName} instead of a valid vector type")
    }

    /** read an array of floats */
    fun parseFloatsDataArray(out: ArrayList<Float>) {
        out.clear()
        if (tokens.isEmpty()) parseError("unexpected empty element", this)
        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count == 0) return

            if (type != 'd' && type != 'f') parseError("expected float or double array (binary)", this)

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * if (type == 'd') 8 else 4)

            if (type == 'd') {
                val d = buff.asDoubleBuffer()
                for (i in 0 until count) out += d[i].toFloat()
            } else if (type == 'f') {
                val f = buff.asFloatBuffer()
                for (i in 0 until count) out += f[i]
            }
            return
        }

        val dim = tokens[0].parseAsDim

        // see notes in ParseVectorDataArray()
        out.ensureCapacity(dim.toInt())

        val a = getRequiredElement(scope, "a", this)

        for (it in a.tokens)
            out += it.parseAsFloat
    }

    /** read an array of ints */
    fun parseIntsDataArray(out: ArrayList<Int>) {
        out.clear()
        if (tokens.isEmpty()) parseError("unexpected empty element", this)
        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count == 0) return

            if (type != 'i') parseError("expected int array (binary)", this)

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * if (type == 'd') 8 else 4)

            val ip = buff.asIntBuffer()
            for (i in 0 until count) out += ip[i]
            return
        }

        val dim = tokens[0].parseAsDim

        // see notes in ParseVectorDataArray()
        out.ensureCapacity(dim.toInt())

        val a = getRequiredElement(scope, "a", this)

        for (it in a.tokens)
            out += it.parseAsInt
    }

    /** read an array of int64_ts   */
    fun parseLongsDataArray(out: ArrayList<Long>) {
        out.clear()
        if (tokens.isEmpty()) parseError("unexpected empty element", this)
        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count == 0) return

            if (type != 'l') parseError("expected long array (binary)")

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * 8)

            val ip = buff.asLongBuffer()
            for (i in 0 until count) out += ip[i]

            return
        }

        val dim = tokens[0].parseAsDim

        // see notes in ParseVectorDataArray()
        out.ensureCapacity(dim.toInt())

        val a = getRequiredElement(scope, "a", this)

        for (it in a.tokens)
            out += it.parseAsInt64
    }

    /** read an array of float3 tuples */
    fun parseVec3DataArray(out: ArrayList<Vec3f>) {
        out.clear()

        if (tokens.isEmpty()) parseError("unexpected empty element", this)

        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count % 3 != 0) parseError("number of floats is not a multiple of three (3) (binary)", this)

            if (count == 0) return

            if (type != 'd' && type != 'f') parseError("expected float or double array (binary)", this)

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * if (type == 'd') 8 else 4)

            val count3 = count / 3
            out.ensureCapacity(count3)

            if (type == 'd') {
                val d = buff.asDoubleBuffer()
                for (i in 0 until count3) out += Vec3f(d.get().toFloat(), d.get().toFloat(), d.get().toFloat())
                // for debugging
                /*for ( size_t i = 0; i < out.size(); i++ ) {
                    aiVector3D vec3( out[ i ] );
                    std::stringstream stream;
                    stream << " vec3.x = " << vec3.x << " vec3.y = " << vec3.y << " vec3.z = " << vec3.z << std::endl;
                    DefaultLogger::get()->info( stream.str() );
                }*/
            } else if (type == 'f') {
                val f = buff.asFloatBuffer()
                for (i in 0 until count3) out += Vec3f(f.get(), f.get(), f.get())
            }
        } else {

            val dim = tokens[0].parseAsDim

            out.ensureCapacity(dim.toInt())

            val a = getRequiredElement(scope, "a", this)

            if (a.tokens.size % 3 != 0)
                parseError("number of floats is not a multiple of three (3)", this)

            var i = 0
            while (i < a.tokens.size)
                out += Vec3f(
                    x = a.tokens[i++].parseAsFloat,
                    y = a.tokens[i++].parseAsFloat,
                    z = a.tokens[i++].parseAsFloat)
        }
    }

    /** read an array of float2 tuples */
    fun parseVec2DataArray(out: ArrayList<Vec2f>) {

        out.clear()

        if (tokens.isEmpty()) parseError("unexpected empty element", this)

        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count % 2 != 0) parseError("number of floats is not a multiple of three (2) (binary)", this)

            if (count == 0) return

            if (type != 'd' && type != 'f') parseError("expected float or double array (binary)", this)

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * if (type == 'd') 8 else 4)

            val count2 = count / 2
            out.ensureCapacity(count2)

            if (type == 'd') {
                val d = buff.asDoubleBuffer()
                for (i in 0 until count2) out += Vec2f(d.get().toFloat(), d.get().toFloat())
                // for debugging
                /*for ( size_t i = 0; i < out.size(); i++ ) {
                    aiVector3D vec3( out[ i ] );
                    std::stringstream stream;
                    stream << " vec3.x = " << vec3.x << " vec3.y = " << vec3.y << " vec3.z = " << vec3.z << std::endl;
                    DefaultLogger::get()->info( stream.str() );
                }*/
            } else if (type == 'f') {
                val f = buff.asFloatBuffer()
                for (i in 0 until count2) out += Vec2f(f.get(), f.get())
            }

        } else {

            val dim = tokens[0].parseAsDim

            out.ensureCapacity(dim.toInt())

            val a = getRequiredElement(scope, "a", this)

            if (a.tokens.size % 2 != 0)
                parseError("number of floats is not a multiple of three (2)", this)

            var i = 0
            while (i < a.tokens.size)
                out += Vec2f(
                    x = a.tokens[i++].parseAsFloat,
                    y = a.tokens[i++].parseAsFloat)
        }
    }

    /** read an array of float4 tuples */
    fun parseVec4DataArray(out: ArrayList<Vec4f>) {
        out.clear()

        if (tokens.isEmpty()) parseError("unexpected empty element", this)

        if (tokens[0].isBinary) {
            begin = tokens[0].begin
            val end = tokens[0].end

            readBinaryDataArrayHead(::begin, end, ::type, ::count)

            if (count % 4 != 0) parseError("number of floats is not a multiple of four (4) (binary)", this)

            if (count == 0) return

            if (type != 'd' && type != 'f') parseError("expected float or double array (binary)", this)

            val buff = readBinaryDataArray(type, count, ::begin, end)

            assert(begin == end && buff.rem == count * if (type == 'd') 8 else 4)

            val count4 = count / 4
            out.ensureCapacity(count4)

            if (type == 'd') {
                val d = buff.asDoubleBuffer()
                for (i in 0 until count4) out += Vec4f(d.get().toFloat(), d.get().toFloat(), d.get().toFloat(), d.get().toFloat())
            } else if (type == 'f') {
                val f = buff.asFloatBuffer()
                for (i in 0 until count4) out += Vec4f(f.get(), f.get(), f.get(), f.get())
            }
        } else {
            val dim = tokens[0].parseAsDim

            out.ensureCapacity(dim.toInt())

            val a = getRequiredElement(scope, "a", this)

            if (a.tokens.size % 4 != 0)
                parseError("number of floats is not a multiple of four (4)", this)

            var i = 0
            while (i < a.tokens.size)
                out += Vec4f(
                    x = a.tokens[i++].parseAsFloat,
                    y = a.tokens[i++].parseAsFloat,
                    z = a.tokens[i++].parseAsFloat,
                    w = a.tokens[i++].parseAsFloat)
        }
    }

    /** read the type code and element count of a binary data array and stop there */
    fun readBinaryDataArrayHead(begin: KMutableProperty0<Int>, end: Int, type: KMutableProperty0<Char>,
                                count: KMutableProperty0<Int>) {
        if (end - begin() < 5) parseError("binary data array is too short, need five (5) bytes for type signature and element count", this)

        // data type
        type.set(buffer[begin()].toInt().toChar())

        // read number of elements
        val len = buffer.getInt(begin() + 1)

        count.set(len)
        begin.set(begin() + 5)
    }

    /** read binary data array, assume cursor points to the 'compression mode' field (i.e. behind the header) */
    fun readBinaryDataArray(type: Char, count: Int, begin: KMutableProperty0<Int>, end: Int): ByteBuffer {
        val encMode = buffer.getInt(begin())
        begin.set(begin() + Int.SIZE_BYTES)

        // next comes the compressed length
        val compLen = buffer.getInt(begin())
        begin.set(begin() + Int.SIZE_BYTES)

        assert(begin() + compLen == end)

        // determine the length of the uncompressed data by looking at the type signature
        val stride = when (type) {
            'f', 'i' -> Float.SIZE_BYTES
            'd', 'l' -> Double.SIZE_BYTES
            else -> throw Exception()
        }

        val fullLength = stride * count
        val buff = Buffer(fullLength)

        if (encMode == 0) {
            assert(fullLength == compLen)
            // plain data, no compression
            for (i in 0 until fullLength)
                buff[i] = buffer[begin() + i]
        } else if (encMode == 1) {
            val input = ByteArray(fullLength) { buffer.get(begin() + it) }
            val decompresser = Inflater().apply { setInput(input) }
            val result = ByteArray(fullLength)
            val resultLength = decompresser.inflate(result)
            decompresser.end()
            for (i in 0 until resultLength)
                buff[i] = result[i]
        }

        begin.set(begin() + compLen)
        assert(begin() == end)

        return buff
    }
}


/** FBX data entity that consists of a 'scope', a collection of not necessarily unique #Element instances.
 *
 *  Example:
 *  @verbatim
 *    GlobalSettings:  {
 *        Version: 1000
 *        Properties70:
 *        [...]
 *    }
 *  @endverbatim  */
class Scope(parser: Parser, topLevel: Boolean = false) {

    val elements = HashMap<String, ArrayList<Element>>()

    init {
        if (!topLevel) {
            val t = parser.currentToken!!
            if (t.type != Tt.OPEN_BRACKET) parseError("expected open bracket", t)
        }

        var n = parser.advanceToNextToken() ?: parseError("unexpected end of file")

        // note: empty scopes are allowed
        while (n.type != Tt.CLOSE_BRACKET) {
            if (n.type != Tt.KEY) parseError("unexpected token, expected TOK_KEY", n)

            elements.getOrPut(n.stringContents, ::arrayListOf) += Element(n, parser)

            // Element() should stop at the next Key token (or right after a Close token)
            val t = parser.currentToken
            if (t == null) {
                if (topLevel) break
                parseError("unexpected end of file", parser.lastToken)
            } else n = t
        }
    }

    operator fun get(index: String) = elements[index]?.get(0)
    fun getArray(index: String) = elements[index]!!
    fun getCollection(index: String) = elements[index] ?: arrayListOf()
}


/** FBX parsing class, takes a list of input tokens and generates a hierarchy
 *  of nested #Scope instances, representing the fbx DOM.*/
class Parser
/** Parse given a token list. Does not take ownership of the tokens -
 *  the objects must persist during the entire parser lifetime */
constructor(val tokens: ArrayList<Token>, val isBinary: Boolean) {

    var last: Token? = null
    var current: Token? = null
    var cursor = 0
    var root = Scope(this, true)

    fun advanceToNextToken(): Token? {
        last = current
        current = tokens.getOrNull(cursor++)
        return current
    }

    val lastToken get() = last
    val currentToken get() = current
}


/** signal parse error, this is always unrecoverable. Throws Error. */
fun parseError(message: String, element: Element? = null): Nothing {
    element?.let { parseError(message, it.keyToken) }
    throw Exception("FBX-Parser $message")
}

fun parseError(message: String, token: Token?): Nothing {
    token?.let { throw Exception(Util.addTokenText("FBX-Parser", message, token)) }
    parseError(message)
}

fun getRequiredElement(sc: Scope, index: String, element: Element? = null) =
    sc[index] ?: parseError("did not find required element \"$index\"", element)