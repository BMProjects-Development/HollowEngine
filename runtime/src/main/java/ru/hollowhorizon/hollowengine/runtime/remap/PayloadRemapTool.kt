package ru.hollowhorizon.hollowengine.runtime.remap

import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.MappingsLoader
import java.io.File
import java.util.jar.JarFile
import kotlin.system.exitProcess

/**
 * Build-time entry point that produces the payload remap table and proves it can be trusted.
 */
object PayloadRemapTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)

        val mappings = options.mappings.inputStream().use(MappingsLoader::loadMappings)
        val work = options.work.apply { mkdirs() }
        val recorded = work.resolve("recorded.jar")

        val result = PayloadRemapTableGenerator.generate(
            payload = options.payload,
            mappings = mappings,
            classpath = options.classpath,
            from = options.from,
            to = options.to,
            output = recorded,
            relocation = options.relocation,
            keep = options.keep.flatMapTo(HashSet(), PayloadRemapTableGenerator::classNames),
        )

        result.shippedTable.write(options.output)
        val relocated = result.shippedTable.classes.size - result.table.classes.size
        println(
            "Remap table: ${result.table.classes.size} classes, ${result.table.methods.size} methods, " +
                "${result.table.fields.size} fields, $relocated relocated " +
                "(${options.output.length() / 1024} KiB gzipped)"
        )

        val applied = work.resolve("applied.jar")
        result.table.applyTo(options.payload, applied)

        val parity = compareBytes(recorded, applied)
        if (parity.isNotEmpty()) {
            System.err.println("Remap table is not equivalent to the mapping tree it was recorded from:")
            parity.take(20).forEach { System.err.println("  $it") }
            System.err.println("  ${parity.size} entries differ in total")
            exitProcess(1)
        }
        println("Parity check passed: table-driven rewrite is byte-identical to the mapping-tree rewrite")

        val reference = options.reference ?: return
        val report = PayloadRemapVerifier.verify(reference, applied, result.table)
        report.writeTo(work.resolve("verification-report.txt"))
        print(report.describe())
        if (!report.isSuccess) {
            System.err.println("Remapped payload does not match ${reference.name}")
            exitProcess(1)
        }
        println("Check passed against ${reference.name}")
    }

    private fun compareBytes(expected: File, actual: File): List<String> = JarFile(expected).use { expectedJar ->
        JarFile(actual).use { actualJar ->
            val expectedNames = expectedJar.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            val actualNames = actualJar.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()

            buildList {
                expectedNames.minus(actualNames).forEach { add("missing $it") }
                actualNames.minus(expectedNames).forEach { add("unexpected $it") }
                expectedNames.intersect(actualNames).forEach { name ->
                    val left = expectedJar.getInputStream(expectedJar.getEntry(name)).use { it.readBytes() }
                    val right = actualJar.getInputStream(actualJar.getEntry(name)).use { it.readBytes() }
                    if (!left.contentEquals(right)) add("differs $name")
                }
            }
        }
    }

    private class Options(
        val payload: File,
        val mappings: File,
        val classpath: List<File>,
        val from: String,
        val to: String,
        val output: File,
        val work: File,
        val reference: File?,
        val relocation: PrefixRelocation,
        val keep: List<File>,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                val values = HashMap<String, String>()
                var index = 0
                while (index < args.size - 1) {
                    values[args[index].removePrefix("--")] = args[index + 1]
                    index += 2
                }

                fun require(name: String) = File(values[name] ?: error("Missing --$name"))

                return Options(
                    payload = require("payload"),
                    mappings = require("mappings"),
                    classpath = values["classpath"].orEmpty()
                        .split(File.pathSeparatorChar)
                        .filter(String::isNotBlank)
                        .map(::File),
                    from = values["from"] ?: "named",
                    to = values["to"] ?: "intermediary",
                    output = require("output"),
                    work = require("work"),
                    reference = values["reference"]?.let(::File),
                    relocation = PrefixRelocation.parse(values["relocate"]),
                    keep = values["keep"].orEmpty()
                        .split(File.pathSeparatorChar)
                        .filter(String::isNotBlank)
                        .map(::File),
                )
            }
        }
    }
}
