package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.PsiFileEx
import com.intellij.psi.impl.PsiManagerEx
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.ide.session.completion.createCompletions
import ru.hollowhorizon.hollowengine.common.ide.session.definition.findDefinition
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.diagnosticCode
import ru.hollowhorizon.hollowengine.common.ide.session.format.formatKotlinCode
import ru.hollowhorizon.hollowengine.common.ide.session.highlight.highlightCode
import ru.hollowhorizon.hollowengine.common.ide.session.highlight.occurrencesCode
import ru.hollowhorizon.hollowengine.common.ide.session.insight.findHoverInfo
import ru.hollowhorizon.hollowengine.common.ide.session.insight.findSignatureHelp
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaRekotLibraryModule
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaScriptModule
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptImports
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

class ScriptingAnalyzerImpl(
    val kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
    val libraries: List<KaRekotLibraryModule>,
    val builtins: Builtins,
    val projectStructureProvider: ProjectStructureProviderImpl,
) : ScriptingAnalyzer {
    val project: Project
        get() = kotlinCoreProjectEnvironment.project
    private val factory = KtPsiFactory(project, eventSystemEnabled = true)

    internal val fileCache = object : LinkedHashMap<String, CachedFile>(5, 0.75f, true) {
        override fun removeEldestEntry(entry: MutableMap.MutableEntry<String, CachedFile>?): Boolean {
            val shouldRemove = size > 5
            if (shouldRemove && entry != null) {
                cleanupFiles(entry.value.relatedFiles)
            }
            return shouldRemove
        }
    }

    internal data class CachedFile(
        val textHash: Int,
        val textLength: Int,
        val file: KtFile,
        val relatedFiles: List<KtFile>,
    )

    private data class CreatedFileGraph(val root: KtFile, val files: List<KtFile>)
    private data class ImportResolution(val files: List<KtFile>, val diagnostics: List<Diagnostic>)

    private val importDiagnostics = IdentityHashMap<KtFile, List<Diagnostic>>()

    private fun getOrCreateFile(name: String, original: String): KtFile {
        val text = original.withUnixLineSeparators()
        val textHash = text.hashCode()
        val textLength = text.length
        val cached = fileCache[name]

        if (cached != null) {
            if (cached.textLength == textLength && cached.textHash == textHash) {
                return cached.file
            }
            fileCache.remove(name)
            cleanupFiles(cached.relatedFiles)
        }

        val graph = createPsiGraph(name, text)
        fileCache[name] = CachedFile(textHash, textLength, graph.root, graph.files)
        return graph.root
    }

    private fun createPsiGraph(name: String, text: String): CreatedFileGraph {
        val files = LinkedHashMap<String, KtFile>()
        val activeFiles = HashSet<String>()
        return try {
            val root = createPsiFile(name, text, files, activeFiles)
            CreatedFileGraph(root, files.values.toList())
        } catch (throwable: Throwable) {
            cleanupFiles(files.values.toList())
            throw throwable
        }
    }

    private fun createPsiFile(
        name: String,
        text: String,
        files: MutableMap<String, KtFile>,
        activeFiles: MutableSet<String>,
    ): KtFile {
        files[name]?.let { return it }

        val file = factory.createFile(name, text)
        files[name] = file
        activeFiles += name
        val imports = try {
            resolveImports(file, files, activeFiles)
        } finally {
            activeFiles -= name
        }
        importDiagnostics[file] = imports.diagnostics

        projectStructureProvider.setModule(
            file, KaScriptModule(
                file, project, buildList {
                    addAll(libraries)
                    addAll(importedModules(imports.files))
                    add(builtins.kaModule)
                }.distinct()
            )
        )
        return file
    }

    /**
     * Modules of imported scripts.
     */
    private fun importedModules(imported: List<KtFile>): List<KaModule> = buildList {
        imported.forEach { file ->
            val module = file.contextModule ?: return@forEach
            add(module)
            addAll(module.directRegularDependencies)
        }
    }

    /**
     * Resolves `@file:Import(...)` the same way the compiler does, through the script registry, and names
     * each imported PSI file with the path the editor itself uses.
     */
    private fun resolveImports(
        file: KtFile,
        files: MutableMap<String, KtFile>,
        activeFiles: MutableSet<String>,
    ): ImportResolution {
        val owner = ScriptRegistry.parse(file.name)
        val importedFiles = ArrayList<KtFile>()
        val diagnostics = ArrayList<Diagnostic>()
        val importedNames = HashSet<String>()
        val seenReferences = HashSet<String>()

        file.annotationEntries.filter { it.typeName == "Import" }.forEach { entry ->
            entry.valueArguments.forEach argumentLoop@{ argument ->
                val expression = argument.getArgumentExpression() ?: return@argumentLoop
                val reference = expression.text.trim('"').trim()
                if (reference.isEmpty() || !seenReferences.add(reference)) return@argumentLoop

                val imported = try {
                    ScriptImports.resolve(owner, reference)
                } catch (exception: IllegalArgumentException) {
                    diagnostics += importDiagnostic(
                        file,
                        expression,
                        exception.message ?: "Invalid script import '$reference'",
                    )
                    return@argumentLoop
                }
                if (imported == null) {
                    diagnostics += importDiagnostic(file, expression, "Cannot resolve the imported script '$reference'")
                    return@argumentLoop
                }

                val source = ScriptRegistry.artifacts(imported)?.sourceFile
                if (source == null) {
                    diagnostics += importDiagnostic(file, expression, "Cannot load the imported script '$reference'")
                    return@argumentLoop
                }

                val importedName = editorPathOf(source)
                if (importedName in activeFiles) {
                    diagnostics += importDiagnostic(
                        file,
                        expression,
                        "Recursive script import cycle through '$reference'",
                    )
                    return@argumentLoop
                }
                if (!importedNames.add(importedName)) return@argumentLoop

                val importedText = try {
                    source.readText().withUnixLineSeparators()
                } catch (exception: IOException) {
                    diagnostics += importDiagnostic(
                        file,
                        expression,
                        exception.message ?: "Cannot read the imported script '$reference'",
                    )
                    return@argumentLoop
                }
                importedFiles += createPsiFile(importedName, importedText, files, activeFiles)
            }
        }
        return ImportResolution(importedFiles, diagnostics)
    }

    private fun importDiagnostic(file: KtFile, element: PsiElement, message: String): Diagnostic {
        val document = file.fileDocument
        val range = element.textRange
        val startLine = document.getLineNumber(range.startOffset)
        val endLine = document.getLineNumber(range.endOffset)
        return Diagnostic(
            Range(
                Position(startLine, range.startOffset - document.getLineStartOffset(startLine)),
                Position(endLine, range.endOffset - document.getLineStartOffset(endLine)),
            ),
            Severity.ERROR,
            message,
        )
    }

    /** The path [HollowIdeModel][ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeModel] opens files by. */
    private fun editorPathOf(file: File): String {
        val root = DirectoryManager.HOLLOW_ENGINE.toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(root)) return file.name
        return root.relativize(path).toString().replace(File.separatorChar, '/')
    }

    internal fun cleanupFile(file: KtFile) {
        importDiagnostics.remove(file)
        projectStructureProvider.removeModule(file)
        (file as? PsiFileEx)?.markInvalidated()
        removeFromPsiManager(file)
    }

    private fun cleanupFiles(files: List<KtFile>) {
        files.asReversed().forEach(::cleanupFile)
    }

    private fun collectImportDiagnostics(name: String, root: KtFile): List<Diagnostic> {
        val relatedFiles = fileCache[name]?.relatedFiles ?: return importDiagnostics[root].orEmpty()
        val rootPosition = Position(0, 0)
        val rootRange = Range(rootPosition, rootPosition)
        return relatedFiles.flatMap { file ->
            importDiagnostics[file].orEmpty().map { diagnostic ->
                if (file === root) diagnostic else diagnostic.copy(
                    range = rootRange,
                    message = "In imported script '${file.name}': ${diagnostic.message}",
                )
            }
        }
    }

    @Synchronized
    override fun highlight(
        name: String,
        text: String,
        offset: Int,
    ): List<TextLine> {
        val file = getOrCreateFile(name, text)
        return highlightCode(file, offset)
    }

    @Synchronized
    override fun occurrences(name: String, text: String, offset: Int): List<OccurrenceRange> {
        val file = getOrCreateFile(name, text)
        return occurrencesCode(file, offset)
    }

    override val canFormat: Boolean get() = true

    @Synchronized
    override fun format(name: String, text: String): String? {
        val file = getOrCreateFile(name, text)
        return formatKotlinCode(file).takeIf { it != file.text }
    }

    @Synchronized
    override fun completions(name: String, text: String, offset: Int, sink: CompletionSink) {
        val file = getOrCreateFile(name, text)
        createCompletions(file, offset, sink)
    }

    @Synchronized
    override fun definition(name: String, text: String, offset: Int): DefinitionLocation? {
        val file = getOrCreateFile(name, text)
        return findDefinition(file, offset)
    }

    @Synchronized
    override fun signatureHelp(name: String, text: String, offset: Int): SignatureHelp? {
        val file = getOrCreateFile(name, text)
        return findSignatureHelp(file, offset)
    }

    @Synchronized
    override fun hover(name: String, text: String, offset: Int): HoverInfo? {
        val file = getOrCreateFile(name, text)
        return findHoverInfo(file, offset)
    }

    @Synchronized
    override fun diagnostic(name: String, text: String): List<Diagnostic> {
        val file = getOrCreateFile(name, text)
        return collectImportDiagnostics(name, file) + diagnosticCode(file)
    }
}

private fun removeFromPsiManager(file: KtFile) {
    val psiManager = com.intellij.psi.PsiManager.getInstance(file.project) as? PsiManagerEx ?: return
    val fileManager = psiManager.fileManager
    fileManager.setViewProvider(file.virtualFile, null)
}

private fun String.withUnixLineSeparators(): String = replace("\r\n", "\n").replace('\r', '\n')

val KtAnnotationEntry.typeName: String? get() = (typeReference?.typeElement as? KtUserType)?.referencedName
