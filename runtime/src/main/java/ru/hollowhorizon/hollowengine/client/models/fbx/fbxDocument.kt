package ru.hollowhorizon.hollowengine.client.models.fbx

class Connection(val insertionOrder: Long, val src: Long, val dest: Long, val prop: String, val doc: Document) :
    Comparable<Connection> {

    init {
        require(doc.objects.contains(src))
        require(doc.objects.contains(dest))
    }

    val sourceObject get() = doc[src]!!.get()
    val destinationObject get() = doc[dest]!!.get()

    val lazySourceObject get() = doc[src]!!
    val lazyDestinationObject get() = doc[dest]!!

    override fun compareTo(other: Connection) = insertionOrder.compareTo(other.insertionOrder)

}

class FileGlobalSettings(val doc: Document, val props: PropertyTable) {
    val upAxis get() = props("UpAxis", 1)
    val upAxisSign get() = props("UpAxisSign", 1)
    val frontAxis get() = props("FrontAxis", 2)
    val frontAxisSign get() = props("FrontAxisSign", 1)
    val coordAxis get() = props("CoordAxis", 0)
    val coordAxisSign get() = props("CoordAxisSign", 1)
    val unitScaleFactor get() = props("UnitScaleFactor", 1f)
}

class Document(val parser: Parser) {
    val warnings = mutableListOf<FbxImportWarning>()

    val objects = mutableMapOf<Long, LazyObject>()

    val templates = HashMap<String, PropertyTable>()
    val srcConnections = mutableMapOf<Long, ArrayList<Connection>>()
    val destConnections = mutableMapOf<Long, ArrayList<Connection>>()

    var fbxVersion = 0
    var creator = ""
    val creationTimeStamp = IntArray(7)

    private val animationStacks = ArrayList<Long>()
    val animationStacksResolved = ArrayList<AnimationStack>()

    var globals: FileGlobalSettings? = null

    init {
        readHeader()
        readPropertyTemplates()

        readGlobalSettings()

        readObjects()
        readConnections()
    }

    operator fun get(id: Long) = objects[id]

    fun getConnectionsByDestinationSequenced(dest: Long) = getConnectionsSequenced(dest, destConnections)

    fun getConnectionsBySourceSequenced(source: Long, classname: String) =
        getConnectionsBySourceSequenced(source, arrayOf(classname))

    fun getConnectionsByDestinationSequenced(dest: Long, classname: String) =
        getConnectionsByDestinationSequenced(dest, arrayOf(classname))

    fun getConnectionsBySourceSequenced(source: Long, classnames: Array<String>) =
        getConnectionsSequenced(source, true, srcConnections, classnames)

    fun getConnectionsByDestinationSequenced(dest: Long, classnames: Array<String>) =
        getConnectionsSequenced(dest, false, destConnections, classnames)

    fun animationStacks(): ArrayList<AnimationStack> {
        if (animationStacksResolved.isNotEmpty() || animationStacks.isEmpty()) return animationStacksResolved

        animationStacksResolved.ensureCapacity(animationStacks.size)
        for (id in animationStacks) {
            val stack = get(id)?.get<AnimationStack>()
            if (stack == null) {
                domWarning("failed to read AnimationStack object")
                continue
            }
            animationStacksResolved += stack
        }
        return animationStacksResolved
    }

    fun getConnectionsSequenced(id: Long, conns: MutableMap<Long, ArrayList<Connection>>): ArrayList<Connection> {
        val range = conns[id] ?: arrayListOf()

        return ArrayList<Connection>(range.size).apply {
            addAll(range)
            sort()
        }
    }

    fun getConnectionsSequenced(
        id: Long,
        isSrc: Boolean,
        conns: MutableMap<Long, ArrayList<Connection>>,
        classnames: Array<String>,
    ): ArrayList<Connection> {
        val temp = ArrayList<Connection>()
        val range = conns[id] ?: return temp

        temp.ensureCapacity(range.size)
        for (it in range) {
            val key = (if (isSrc) it.lazyDestinationObject else it.lazySourceObject).element.keyToken

            if (key.stringContents !in classnames) continue
            temp += it
        }
        temp.sort()
        return temp
    }

    fun readHeader() {
        val sc = parser.root
        val eHead = sc["FBXHeaderExtension"]
        if (eHead?.compound == null) domError("no FBXHeaderExtension dictionary found")
        val sHead = eHead.compound!!
        fbxVersion = getRequiredElement(sHead, "FBXVersion", eHead)[0].parseAsInt

        if (fbxVersion < lowerSupportedVersion) domError("unsupported, old format version, supported version range is 7100..7700")
        if (fbxVersion > upperSupportedVersion) domWarning("unsupported, newer format version, supported version range is 7100..7700, trying to read it nevertheless")

        sHead["Creator"]?.let { creator = it[0].parseAsString }

        sHead["CreationTimeStamp"]?.let {
            it.compound?.let {
                creationTimeStamp[0] = it["Year"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[1] = it["Month"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[2] = it["Day"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[3] = it["Hour"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[4] = it["Minute"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[5] = it["Second"]?.get(0)?.parseAsInt ?: 0
                creationTimeStamp[6] = it["Millisecond"]?.get(0)?.parseAsInt ?: 0
            }
        }
    }

    fun readObjects() {
        val sc = parser.root
        val eObjects = sc["Objects"]
        if (eObjects?.compound == null) domError("no Objects dictionary found")

        objects[0L] = LazyObject(0L, eObjects, this)

        val sObjects = eObjects.compound!!
        for (el in sObjects.elements) {
            val key = el.key
            for (e in el.value) {
                val tok = e.tokens

                if (tok.isEmpty()) domError("expected ID after object key", e)

                val id = tok[0].parseAsId
                if (id == 0L) domError("encountered object with implicitly defined id 0", e)
                if (objects.contains(id)) domError("duplicate object id $id", e)

                objects[id] = LazyObject(id, e, this)

                if (key == "AnimationStack") animationStacks += id
            }
        }
    }

    fun readPropertyTemplates() {
        val eDefs = parser.root["Definitions"]
        if (eDefs?.compound == null) {
            domWarning("no Definitions dictionary found")
            return
        }

        val sDefs = eDefs.compound!!
        val oTypes = sDefs.getCollection("ObjectType")
        for (el in oTypes) {
            val sc = el.compound
            if (sc == null) {
                domWarning("expected nested scope in ObjectType, ignoring", el)
                continue
            }
            val tok = el.tokens
            if (tok.isEmpty()) {
                domWarning("expected name for ObjectType element, ignoring", el)
                continue
            }
            val oName = tok[0].parseAsString
            val templs = sc.getCollection("PropertyTemplate")
            for (e in templs) {
                val s = e.compound
                if (s == null) {
                    domWarning("expected nested scope in PropertyTemplate, ignoring", e)
                    continue
                }
                val t = e.tokens
                if (t.isEmpty()) {
                    domWarning("expected name for PropertyTemplate element, ignoring", e)
                    continue
                }
                val pName = t[0].parseAsString
                s["Properties70"]?.let {
                    val props = PropertyTable(it, null)
                    templates["$oName.$pName"] = props
                }
            }
        }
    }

    fun readConnections() {
        val sc = parser.root
        val eConns = sc["Connections"]
        if (eConns?.compound == null) domError("no Connections dictionary found")

        var insertionOrder = 0L
        val sConns = eConns.compound!!
        val conns = sConns.getCollection("C")
        for (el in conns) {
            val type = el[0].parseAsString

            if (type !in setOf("OO", "OP")) continue

            val src = el[1].parseAsId
            val dest = el[2].parseAsId
            val prop = if (type == "OP") el[3].parseAsString else ""

            if (!objects.contains(src)) {
                domWarning("source object for connection does not exist", el)
                continue
            }

            if (!objects.contains(dest)) {
                domWarning("destination object for connection does not exist", el)
                continue
            }

            val c = Connection(insertionOrder++, src, dest, prop, this)
            srcConnections.getOrPut(src, ::arrayListOf) += c
            destConnections.getOrPut(dest, ::arrayListOf) += c
        }
    }

    fun readGlobalSettings() {
        val sc = parser.root
        val eHead = sc["GlobalSettings"]
        if (eHead?.compound == null) {
            domWarning("no GlobalSettings dictionary found")
            globals = FileGlobalSettings(this, PropertyTable())
            return
        }

        val props = getPropertyTable(this, "", eHead, eHead.compound!!, true)

        if (props.lazyProps.isEmpty() && props.props.isEmpty()) domError("GlobalSettings dictionary contains no property table")

        globals = FileGlobalSettings(this, props)
    }

    companion object {
        const val lowerSupportedVersion = 7100
        const val upperSupportedVersion = 7700
    }
}
