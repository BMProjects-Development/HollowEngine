package ru.hollowhorizon.hollowengine.runtime.remap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.io.File
import java.util.jar.JarFile

/**
 * Compares two remapped payloads by what their bytecode refers to rather than by bytes.
 */
object PayloadRemapVerifier {
    private class ClassFacts(val facts: Set<String>, val stale: Set<String>)

    class Difference(val entry: String, val onlyInExpected: List<String>, val onlyInActual: List<String>)

    class Report(
        val comparedClasses: Int,
        val missingEntries: List<String>,
        val extraEntries: List<String>,
        val differences: List<Difference>,
        val staleInExpected: List<String>,
        val staleInActual: List<String>,
    ) {
        val staleRegressions: List<String> get() = staleInActual.minus(staleInExpected.toSet())

        val isSuccess: Boolean
            get() = missingEntries.isEmpty() && extraEntries.isEmpty() &&
                differences.isEmpty() && staleRegressions.isEmpty()

        fun describe(limit: Int = 10): String = buildString {
            appendLine("Compared $comparedClasses classes")
            if (missingEntries.isNotEmpty()) {
                appendLine("Missing entries (${missingEntries.size}): ${missingEntries.take(limit)}")
            }
            if (extraEntries.isNotEmpty()) {
                appendLine("Unexpected entries (${extraEntries.size}): ${extraEntries.take(limit)}")
            }
            if (staleInExpected.isNotEmpty()) {
                appendLine(
                    "Metadata the reference build leaves unmapped (${staleInExpected.size}): " +
                        "${staleInExpected.take(limit)}"
                )
            }
            if (staleInActual.isNotEmpty()) {
                appendLine("Metadata both builds leave unmapped (${staleInActual.size}): ${staleInActual.take(limit)}")
            }
            if (staleRegressions.isNotEmpty()) {
                appendLine("Metadata only we leave unmapped (${staleRegressions.size}): ${staleRegressions.take(limit)}")
            }
            differences.take(limit).forEach { difference ->
                appendLine("  ${difference.entry}")
                difference.onlyInExpected.take(limit).forEach { appendLine("    expected: $it") }
                difference.onlyInActual.take(limit).forEach { appendLine("    actual:   $it") }
            }
            if (differences.size > limit) appendLine("  ... and ${differences.size - limit} more classes")
        }
    }

    fun verify(expected: File, actual: File, table: PayloadRemapTable): Report {
        val expectedClasses = readClasses(expected, table)
        val actualClasses = readClasses(actual, table)

        val missing = expectedClasses.keys.minus(actualClasses.keys).sorted()
        val extra = actualClasses.keys.minus(expectedClasses.keys).sorted()
        val shared = expectedClasses.keys.intersect(actualClasses.keys).sorted()

        val differences = shared.mapNotNull { entry ->
            val expectedFacts = expectedClasses.getValue(entry).facts
            val actualFacts = actualClasses.getValue(entry).facts
            if (expectedFacts == actualFacts) return@mapNotNull null
            Difference(
                entry = entry,
                onlyInExpected = expectedFacts.minus(actualFacts).sorted(),
                onlyInActual = actualFacts.minus(expectedFacts).sorted(),
            )
        }

        return Report(
            comparedClasses = expectedClasses.size,
            missingEntries = missing,
            extraEntries = extra,
            differences = differences,
            staleInExpected = staleNames(expectedClasses),
            staleInActual = staleNames(actualClasses),
        )
    }

    private fun staleNames(classes: Map<String, ClassFacts>): List<String> = classes.entries
        .filter { it.value.stale.isNotEmpty() }
        .flatMap { (entry, facts) -> facts.stale.map { "$entry $it" } }
        .sorted()

    private fun readClasses(jar: File, table: PayloadRemapTable): Map<String, ClassFacts> = JarFile(jar).use { archive ->
        val tracked = table.classes.keys + table.classes.values
        archive.entries().asSequence()
            .filter { it.name.endsWith(".class") }
            .associate { entry ->
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                entry.name to facts(bytes, tracked, table.classes)
            }
    }

    private fun facts(bytes: ByteArray, tracked: Set<String>, canonical: Map<String, String>): ClassFacts {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        val metadata = metadataNames(node, tracked)
        val facts = buildSet {
            add("class ${node.name} ${node.superName} ${node.interfaces?.sorted()}")
            add("signature ${node.signature}")
            node.fields?.forEach { add("field ${it.name} ${it.desc} ${it.signature}") }
            node.methods?.forEach { method ->
                add("method ${method.name} ${method.desc} ${method.signature}")
                method.tryCatchBlocks?.forEach { block: TryCatchBlockNode -> block.type?.let { add("catch $it") } }
                method.instructions.forEach { instruction ->
                    when (instruction) {
                        is MethodInsnNode -> add("call ${instruction.owner}.${instruction.name}${instruction.desc}")
                        is FieldInsnNode -> add("access ${instruction.owner}.${instruction.name} ${instruction.desc}")
                        is TypeInsnNode -> add("type ${instruction.desc}")
                        is MultiANewArrayInsnNode -> add("array ${instruction.desc}")
                        is LdcInsnNode -> (instruction.cst as? Type)?.let { add("constant ${it.descriptor}") }
                        is InvokeDynamicInsnNode -> {
                            add("indy ${instruction.name}${instruction.desc}")
                            instruction.bsmArgs.forEach { argument ->
                                when (argument) {
                                    is Handle -> add("handle ${argument.owner}.${argument.name}${argument.desc}")
                                    is Type -> add("handle-type ${argument.descriptor}")
                                }
                            }
                        }
                    }
                }
            }
            metadata.forEach { add("metadata ${canonical[it] ?: it}") }
        }

        return ClassFacts(facts, metadata.filterTo(HashSet()) { it in canonical })
    }

    private fun metadataNames(node: ClassNode, tracked: Set<String>): Set<String> {
        val annotations = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()
        val metadata = annotations.firstOrNull { it.desc == "Lkotlin/Metadata;" } ?: return emptySet()
        val values = metadata.values ?: return emptySet()

        for (index in values.indices step 2) {
            if (values[index] != "d2") continue

            @Suppress("UNCHECKED_CAST")
            val strings = values[index + 1] as? List<String> ?: return emptySet()
            return strings.flatMapTo(HashSet()) { trackedNames(it, tracked) }
        }

        return emptySet()
    }
}

/** Writes every difference, so a failing gate can be inspected instead of guessed at. */
fun PayloadRemapVerifier.Report.writeTo(target: File) {
    target.parentFile?.mkdirs()
    target.bufferedWriter().use { writer ->
        writer.appendLine("compared $comparedClasses classes")
        missingEntries.forEach { writer.appendLine("missing $it") }
        extraEntries.forEach { writer.appendLine("extra $it") }
        staleInExpected.forEach { writer.appendLine("stale-reference $it") }
        staleInActual.forEach { writer.appendLine("stale-ours $it") }
        differences.forEach { difference ->
            difference.onlyInExpected.forEach { writer.appendLine("expected ${difference.entry} $it") }
            difference.onlyInActual.forEach { writer.appendLine("actual ${difference.entry} $it") }
        }
    }
}

private fun trackedNames(value: String, tracked: Set<String>): List<String> =
    value.split(*NAME_SEPARATORS)
        .flatMap { token ->
            val name = token.removePrefix("L")
            listOf(token, name, name.replace('.', '$'))
        }
        .filter { it in tracked }

private val NAME_SEPARATORS = charArrayOf(';', '(', ')', '[', '<', '>', ',', ':', ' ')
