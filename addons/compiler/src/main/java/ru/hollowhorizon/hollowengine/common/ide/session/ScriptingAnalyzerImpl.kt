package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
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
import ru.hollowhorizon.hollowengine.common.ide.session.completion.checkpointNameCompletions
import ru.hollowhorizon.hollowengine.common.ide.session.completion.createCompletions
import ru.hollowhorizon.hollowengine.common.ide.session.completion.findAcceptedExpressionTypes
import ru.hollowhorizon.hollowengine.common.ide.session.definition.LibraryNavigation
import ru.hollowhorizon.hollowengine.common.ide.session.definition.findDefinition
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.diagnosticCode
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.intentionActions
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.syntaxDiagnostics
import ru.hollowhorizon.hollowengine.common.ide.session.format.formatKotlinCode
import ru.hollowhorizon.hollowengine.common.ide.session.highlight.highlightCode
import ru.hollowhorizon.hollowengine.common.ide.session.highlight.occurrencesCode
import ru.hollowhorizon.hollowengine.common.ide.session.insight.findHoverInfo
import ru.hollowhorizon.hollowengine.common.ide.session.insight.findSignatureHelp
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaKotlinSourceModule
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaRekotLibraryModule
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaScriptModule
import ru.hollowhorizon.hollowengine.common.ide.session.structure.editorStructure
import ru.hollowhorizon.hollowengine.common.ide.session.structure.kotlinGutterTargets
import ru.hollowhorizon.hollowengine.common.ide.session.symbols.searchSymbols
import ru.hollowhorizon.hollowengine.common.ide.session.symbols.symbolSource
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptImportError
import ru.hollowhorizon.hollowengine.common.scripting.source.SandboxScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptImports
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.io.IOException
import java.util.*

/** [lock] is shared with the Java analyzer: both build PSI in the same project. */
class ScriptingAnalyzerImpl internal constructor(
    val kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
    val libraries: List<KaRekotLibraryModule>,
    val builtins: Builtins,
    val projectStructureProvider: ProjectStructureProviderImpl,
    internal val navigation: LibraryNavigation,
    private val lock: Any,
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
    private val librarySources = LinkedHashSet<KtFile>()
    private val sourceDependencies by lazy { libraries + builtins.kaModule }
    private val classpath by lazy { libraries.filterNot { it.isSdk } }

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
        if (!file.isScript()) {
            if (SourceDocumentPath.parse(name) != null) {
                librarySources += file
                updateLibrarySourcesModule()
            } else {
                projectStructureProvider.setModule(
                    file,
                    KaKotlinSourceModule(project, listOf(file), sourceDependencies, classpath)
                )
            }
            return file
        }
        activeFiles += name
        val imports = try {
            resolveImports(file, files, activeFiles)
        } finally {
            activeFiles -= name
        }
        importDiagnostics[file] = imports.diagnostics

        val dependencies = buildList {
            addAll(libraries)
            addAll(importedModules(imports.files))
            add(builtins.kaModule)
        }.distinct()
        projectStructureProvider.setModule(file, KaScriptModule(file, project, dependencies))
        return file
    }

    /**
     * Opened library sources share one module, rebuilt whenever the set changes, so each sees the
     * declarations of the others even where the classpath has no binary for them.
     */
    private fun updateLibrarySourcesModule() {
        if (librarySources.isEmpty()) return
        val module = KaKotlinSourceModule(project, librarySources.toList(), sourceDependencies, classpath)
        librarySources.forEach { projectStructureProvider.setModule(it, module) }
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
                val importedFile = createPsiFile(importedName, importedText, files, activeFiles)
                if (file.isSharedScript() && !importedFile.isSharedScript()) {
                    diagnostics += importDiagnostic(file, expression, sharedScriptImportError(file.name, importedName))
                }
                importedFiles += importedFile
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
        ScriptRegistry.idOf(file)?.takeIf { it.namespace != ScriptRegistry.sandboxNamespace }?.let { return it.qualified }
        val root = DirectoryManager.HOLLOW_ENGINE.toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(root)) return file.name
        return root.relativize(path).toString().replace(File.separatorChar, '/')
    }

    internal fun cleanupFile(file: KtFile) {
        importDiagnostics.remove(file)
        projectStructureProvider.removeModule(file)
        if (librarySources.remove(file)) updateLibrarySourcesModule()
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

    override fun highlight(name: String, text: String, offset: Int): List<TextLine> = synchronized(lock) {
        highlightCode(getOrCreateFile(name, text), offset)
    }

    override fun occurrences(name: String, text: String, offset: Int): List<OccurrenceRange> = synchronized(lock) {
        occurrencesCode(getOrCreateFile(name, text), offset)
    }

    override val canFormat: Boolean get() = true

    override fun format(name: String, text: String): String? = synchronized(lock) {
        val file = getOrCreateFile(name, text)
        formatKotlinCode(file).takeIf { it != file.text }
    }

    override fun completions(name: String, text: String, offset: Int, sink: CompletionSink) = synchronized(lock) {
        val file = getOrCreateFile(name, text)
        val checkpoints = checkpointNameCompletions(file, offset)
        if (checkpoints != null) sink.emit(checkpoints) else createCompletions(file, offset, sink)
        Unit
    }

    override fun definition(name: String, text: String, offset: Int): DefinitionLocation? = synchronized(lock) {
        findDefinition(getOrCreateFile(name, text), offset)
    }

    override fun structure(name: String, text: String): EditorStructure = synchronized(lock) {
        editorStructure(getOrCreateFile(name, text))
    }

    override fun gutterTargets(name: String, text: String, offset: Int): List<GutterNavigationTarget> =
        synchronized(lock) {
            kotlinGutterTargets(getOrCreateFile(name, text), offset)
        }

    override fun acceptedExpressionTypes(name: String, text: String, offset: Int, types: List<String>): List<String>? =
        synchronized(lock) { findAcceptedExpressionTypes(getOrCreateFile(name, text), offset, types) }

    override fun signatureHelp(name: String, text: String, offset: Int): SignatureHelp? = synchronized(lock) {
        findSignatureHelp(getOrCreateFile(name, text), offset)
    }

    override fun hover(name: String, text: String, offset: Int): HoverInfo? = synchronized(lock) {
        findHoverInfo(getOrCreateFile(name, text), offset)
    }

    override fun diagnostic(name: String, text: String): List<Diagnostic> = synchronized(lock) {
        val file = getOrCreateFile(name, text)
        if (SourceDocumentPath.parse(name) != null) syntaxDiagnostics(file)
        else collectImportDiagnostics(name, file) + diagnosticCode(file)
    }

    override fun intentionActions(name: String, text: String, offset: Int): List<DiagnosticFix> = synchronized(lock) {
        intentionActions(getOrCreateFile(name, text), offset)
    }

    override fun searchSymbols(query: String, limit: Int): List<SymbolMatch> = synchronized(lock) {
        searchSymbols(getOrCreateFile(SymbolLookupScript, ""), query, limit)
    }

    override fun symbolSource(qualifiedName: String): DefinitionLocation? = synchronized(lock) {
        symbolSource(getOrCreateFile(SymbolLookupScript, ""), qualifiedName)
    }

    private companion object {
        /** An empty sandbox script, so name lookups see the classpath the project's own scripts compile against. */
        val SymbolLookupScript = "${SandboxScriptSource.SCRIPTS_DIRECTORY}/.symbol-lookup.kts"
    }
}

private fun removeFromPsiManager(file: KtFile) {
    val psiManager = PsiManager.getInstance(file.project) as? PsiManagerEx ?: return
    val fileManager = psiManager.fileManager
    fileManager.setViewProvider(file.virtualFile, null)
}

private fun String.withUnixLineSeparators(): String = replace("\r\n", "\n").replace('\r', '\n')

val KtAnnotationEntry.typeName: String? get() = (typeReference?.typeElement as? KtUserType)?.referencedName
