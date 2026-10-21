package ru.hollowhorizon.hollowengine.common.ide.session.completion

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.scopes.KaTypeScope
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression
import org.jetbrains.kotlin.types.Variance
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl
import ru.hollowhorizon.hollowengine.common.ide.session.completion.util.KeywordCompletion
import ru.hollowhorizon.hollowengine.common.ide.session.index.JavaClassName
import ru.hollowhorizon.hollowengine.common.ide.session.index.JavaClassNameIndex
import ru.hollowhorizon.hollowengine.common.ide.session.kaModule
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaScriptModule
import ru.hollowhorizon.hollowengine.common.scripting.ide.*

/**
 * Collects completions in phases, closest to the caret first, and hands each phase to [sink] as soon
 * as it is done. Walking the class indices is what takes seconds, so locals and members reach the
 * popup long before it finishes.
 */
fun ScriptingAnalyzerImpl.createCompletions(file: KtFile, offset: Int, sink: CompletionSink) {
    val safeOffset = offset.coerceIn(0, file.textLength)
    val file = createFileForCompletion(file, safeOffset)
    val element = file.findElementAt(safeOffset) ?: (if (safeOffset > 0) file.findElementAt(safeOffset - 1) else null)
    ?: return
    val ktElement = element.parentOfType<KtElement>(withSelf = true) ?: return

    try {
        analyzeCopy(ktElement, KaDanglingFileResolutionMode.PREFER_SELF) {
            createItems(file, element, ktElement, sink)
        }
    } catch (_: CompletionCancelledException) {
        // The caret moved on; whatever this request had left to say is no longer wanted.
    } finally {
        cleanupFile(file)
    }
}

internal fun ScriptingAnalyzerImpl.createFileForCompletion(original: KtFile, offset: Int): KtFile {
    val safeOffset = offset.coerceIn(0, original.textLength)
    val textWithInsertedFakeIdentifier = original.text.replaceRange(safeOffset, safeOffset, COMPLETION_FAKE_IDENTIFIER)
    val copyKtFile = KtPsiFactory(original.project, markGenerated = true, eventSystemEnabled = true).createFile(
        original.name, textWithInsertedFakeIdentifier
    )

    copyKtFile.contextModule = KaScriptModule(
        copyKtFile,
        project,
        (original.contextModule as? KaScriptModule)?.binaryDependencies ?: emptyList(),
    )
    copyKtFile.virtualFile.kaModule = copyKtFile.contextModule
    copyKtFile.originalFile = original
    return copyKtFile
}

context(kaSession: KaSession)
private fun createItems(ktFile: KtFile, element: PsiElement, parentKtElement: KtElement, sink: CompletionSink): Unit =
    with(kaSession) {
        val position = getPosition(parentKtElement) ?: run {
            sink.emit(completeKeywords(element, position = null))
            return
        }
        val filter = when (val prefix = position.prefix) {
            null -> { _: Name -> true }
            else -> { name: Name -> matchesPrefix(prefix, name.asString()) }
        }

        val applicabilityChecker = when (position) {
            is CompletionPosition.AfterDot -> position.nameExpression?.let {
                createExtensionCandidateChecker(
                    ktFile, it, position.receiver
                )
            } ?: return

            is CompletionPosition.Identifier -> createExtensionCandidateChecker(
                ktFile,
                parentKtElement as KtSimpleNameExpression,
                explicitReceiver = null,
            )

            else -> null
        }
        val visibilityChecker = when (position) {
            is CompletionPosition.AfterDot -> createUseSiteVisibilityChecker(
                ktFile.symbol, position.receiver, position.nameExpression ?: position.receiver
            )

            is CompletionPosition.Identifier -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)

            is CompletionPosition.NestedType -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)

            is CompletionPosition.Type -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)

            // Для пакетов проверка видимости не актуальна
            is CompletionPosition.Package -> null
        }

        val symbolFilter: CompletionItemsCollector.SymbolFilter = when (position) {
            is CompletionPosition.Type if position.isAnnotation -> CompletionItemsCollector.SymbolFilter { symbol ->
                with(contextOf<KaSession>()) {
                    when (symbol) {
                        is KaClassSymbol -> symbol.classKind == KaClassKind.ANNOTATION_CLASS
                        is KaTypeAliasSymbol -> symbol.expandedType.expandedSymbol?.classKind == KaClassKind.ANNOTATION_CLASS
                        else -> false
                    }
                }
            }

            is CompletionPosition.AfterDot -> CompletionItemsCollector.SymbolFilter { symbol ->
                if (position.isInfixContext) {
                    symbol is KaNamedFunctionSymbol && symbol.isInfix
                } else true
            }

            else -> CompletionItemsCollector.SymbolFilter { true }
        }

        val reference = element.parentOfType<KtSimpleNameExpression>(withSelf = true)
        val call = reference?.parent as? KtCallExpression
        val existingCall = call?.calleeExpression == reference && (call?.valueArgumentList != null || call?.lambdaArguments?.isNotEmpty() == true)
        val referenceOnly = reference?.parent is KtCallableReferenceExpression ||
            element.parentOfType<KtImportDirective>(withSelf = true) != null
        val collector = CompletionItemsCollector(applicabilityChecker, visibilityChecker, filter, symbolFilter, sink,
            insertCallSyntax = !existingCall && !referenceOnly)
        with(collector) {
            when (position) {
                is CompletionPosition.AfterDot -> completeAfterDot(
                    position, ktFile, parentKtElement, element.parentOfType<KtPackageDirective>(withSelf = true) != null
                )

                is CompletionPosition.Identifier -> {
                    phase(CompletionCloseness.KEYWORD)
                    add(completeKeywords(parentKtElement, position))
                    if (matchesPrefix(position.prefix, "this")) completeThisKeyword(ktFile, parentKtElement)

                    completeScopeContext(ktFile, parentKtElement)

                    phase(CompletionCloseness.IMPORTED)
                    completeClassesWithImport(ktFile)
                }

                is CompletionPosition.Type -> completeType(ktFile, parentKtElement, filter, position.isAnnotation)
                is CompletionPosition.NestedType -> {
                    phase(CompletionCloseness.MEMBER)
                    completeNestedType(position)
                }

                is CompletionPosition.Package -> {
                    phase(CompletionCloseness.SCOPE)
                    completePackage(ktFile, position.fqName, filter, position.onlyPackages)
                }
            }
        }
        collector.flush()
    }

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeThisKeyword(file: KtFile, element: KtElement) = with(kaSession) {
    val scopeContext = file.scopeContext(element)

    var first = true
    for (implicitReceiver in scopeContext.implicitReceivers) {
        val receiverType = implicitReceiver.type
        val typeText = receiverType.render(position = Variance.IN_VARIANCE)
        val className = receiverThisLabel(implicitReceiver.ownerSymbol, receiverType)

        val thisName = if (first) "this" else "this@$className"

        add(declarationCompletionItem {
            show = thisName
            name = thisName
            insert = thisName
            tail = typeText
            tag = CompletionItemTag.KEYWORD
            import = false
            this.closeness = CompletionCloseness.LOCAL
        })

        first = false
    }

    completeQualifiedThis(file, element)
}

context(kaSession: KaSession)
private fun receiverThisLabel(ownerSymbol: KaSymbol, receiverType: KaType): String = with(kaSession) {
    val typeName = receiverType.expandedSymbol?.name?.asString()

    val isScriptReceiver = typeName == null || !typeName.isKotlinIdentifier()
    if (isScriptReceiver) {
        return receiverType.directSupertypes
            .firstNotNullOfOrNull { it.expandedSymbol?.name?.asString()?.takeIf { name -> name != "Any" } }
            ?: typeName
            ?: ownerSymbol.name?.asString()
            ?: "Unknown"
    }

    return typeName
}

private fun String.isKotlinIdentifier(): Boolean =
    isNotEmpty() && this[0].isJavaIdentifierStart() && all { it.isJavaIdentifierPart() }

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeQualifiedThis(file: KtFile, element: KtElement) = with(kaSession) {
    // Находим все родительские declaration'ы, которые могут иметь this
    var current: PsiElement? = element
    while (current != null) {
        when (current) {
            is KtClassOrObject -> {
                val classSymbol = current.symbol as? KaClassSymbol
                classSymbol?.let { symbol ->
                    val className = symbol.name?.asString() ?: "Unknown"
                    val typeText = symbol.defaultType.render(position = Variance.IN_VARIANCE)

                    add(declarationCompletionItem {
                        show = "this@$className"
                        name = "this@$className"
                        insert = "this@$className"
                        tail = typeText
                        import = false
                        tag = CompletionItemTag.KEYWORD
                        this.closeness = CompletionCloseness.LOCAL
                    })
                }
            }

            is KtFunction -> {
                val functionName = current.name ?: "lambda"
                add(declarationCompletionItem {
                    show = "this@$functionName"
                    name = "this@$functionName"
                    insert = "this@$functionName"
                    import = false
                    tail = " (qualified this for function $functionName)"
                    tag = CompletionItemTag.KEYWORD
                    this.closeness = CompletionCloseness.LOCAL
                })
            }
        }
        current = current.parent
    }
}

private fun completeKeywords(
    element: PsiElement,
    position: CompletionPosition?,
): List<CompletionItem.Keyword> {
    runCatching {
        val matcher = when {
            position == null -> when {
                element.tokenType == KtTokens.IDENTIFIER -> {
                    element.text.removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER).let(::PlainPrefixMatcher)
                }

                else -> return emptyList()
            }

            else -> position.prefix?.let(::PlainPrefixMatcher) ?: PrefixMatcher.ALWAYS_TRUE
        }
        val result = mutableListOf<CompletionItem.Keyword>()
        KeywordCompletion().complete(
            element,
            matcher,
            isJvmModule = true,
        ) { lookupElement ->
            val presentation = LookupElementPresentation().also { lookupElement.renderElement(it) }
            val text = presentation.itemText ?: lookupElement.lookupString

            // Добавляем специальную обработку для ключевого слова "this"
            if (text == "this") {
                return@complete
            }

            result += keywordCompletionItem {
                textToShow = text.trim()
                name = text.trim()
                textToInsert = text
            }
        }
        return result
    }

    return emptyList() // do not fail the whole completion when one item fails, not well tested
}

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeAfterDot(
    position: CompletionPosition.AfterDot,
    file: KtFile,
    element: KtElement,
    onlyPackages: Boolean,
) = with(kaSession) {
    val receiverTarget = when (val receiver = position.receiver) {
        is KtDotQualifiedExpression -> receiver.selectorExpression?.mainReference?.resolveToSymbol()
        else -> receiver.mainReference?.resolveToSymbol()
    }

    when (receiverTarget) {
        is KaClassSymbol -> {
            phase(CompletionCloseness.MEMBER)
            completeStaticMembers(receiverTarget)

            if (receiverTarget.classKind == KaClassKind.OBJECT) {
                completeScope(receiverTarget.combinedMemberScope)
            }

            receiverTarget.staticDeclaredMemberScope.classifiers.filterIsInstance<KaClassSymbol>().forEach {
                if (it.classKind == KaClassKind.COMPANION_OBJECT) {
                    completeScope(it.combinedMemberScope)
                } else {
                    add(it)
                }
            }
        }

        is KaPackageSymbol -> {
            phase(CompletionCloseness.SCOPE)
            completePackage(file, receiverTarget.fqName, nameFilter, onlyPackages)
        }

        else -> {
            val receiverType = position.receiver.expressionType

            if (receiverType == null || receiverType is org.jetbrains.kotlin.analysis.api.types.KaErrorType) return@with

            phase(CompletionCloseness.MEMBER)
            receiverType.scope?.let { completeTypeScope(it) }

            if (position.prefix != null) {
                phase(CompletionCloseness.SCOPE)
                val scopeContext = file.scopeContext(element)
                for (scope in scopeContext.scopes) {
                    add(scope.scope.callables(nameFilter).filterShadowedJavaFields().filter { it.isExtension })
                }
            }

            phase(CompletionCloseness.IMPORTED)
            add(getKotlinDeclarationsFromIndex(file, nameFilter).filter { symbol ->
                symbol is KaCallableSymbol && symbol.isExtension
            }) { withImport() }
        }
    }
}

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeStaticMembers(kaClassSymbol: KaClassSymbol) = with(kaSession) {
    add(kaClassSymbol.staticDeclaredMemberScope.callables(nameFilter).filterShadowedJavaFields())
}

context(session: KaSession)
private fun CompletionItemsCollector.completeClassesWithImport(
    file: KtFile,
    annotationsOnly: Boolean = false,
) = with(session) {
    val kotlinClassFqNames = mutableSetOf<String>()
    for (declaration in getKotlinDeclarationsFromIndex(file, nameFilter)) {
        if (declaration is KaClassLikeSymbol) {
            declaration.importableFqName?.asString()?.let(kotlinClassFqNames::add)
        }
        add(declaration) { withImport() }
    }

    JavaClassNameIndex.getInstance(file.project).classes(nameFilter, annotationsOnly)
        .filterNot { javaClass -> javaClass.fqName in kotlinClassFqNames }
        .forEach { javaClass -> addJavaClass(javaClass, import = true) }
}

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeScopeContext(file: KtFile, element: KtElement) = with(kaSession) {
    val scopeContext = file.scopeContext(element)
    phase(CompletionCloseness.SCOPE)
    for (scope in scopeContext.scopes) {
        add(scope.scope.callables(nameFilter).filterShadowedJavaFields())
        add(scope.scope.classifiers(nameFilter))
    }
    phase(CompletionCloseness.MEMBER)
    for (implicitReceiver in scopeContext.implicitReceivers) {
        implicitReceiver.type.scope?.let { completeTypeScope(it) }
    }
}

context(session: KaSession)
internal fun getKotlinDeclarationsFromIndex(ktFile: KtFile, filter: (Name) -> Boolean): Sequence<KaDeclarationSymbol> {
    with(session) {
        val factory = KotlinDeclarationProviderFactory.getInstance(ktFile.project)
        val declarationProvider = factory.createDeclarationProvider(analysisScope, useSiteModule)
        val kotlinClasses = sequence {
            for (packageName in declarationProvider.computePackageNamesWithTopLevelCallables()!!) {
                val packageFqName = FqName(packageName)
                for (name in declarationProvider.getTopLevelCallableNamesInPackage(packageFqName)) {
                    if (!filter(name)) continue
                    val callableId = CallableId(packageFqName, name)
                    declarationProvider.getTopLevelFunctions(callableId).forEach { yield(it.symbol) }
                    declarationProvider.getTopLevelProperties(callableId).forEach { yield(it.symbol) }
                }
            }

            for (packageName in declarationProvider.computePackageNamesWithTopLevelClassifiers()!!) {
                val packageFqName = FqName(packageName)
                for (name in declarationProvider.getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName)) {
                    if (!filter(name)) continue
                    val classId = ClassId(packageFqName, name)
                    declarationProvider.getClassLikeDeclarationByClassId(classId)?.let { yield(it.symbol) }
                }
            }
        }

        return kotlinClasses
    }
}


context(kaSession: KaSession)
private fun CompletionItemsCollector.completePackage(
    ktFile: KtFile,
    fqName: FqName,
    filter: (Name) -> Boolean,
    onlyPackages: Boolean,
) = with(kaSession) {
    val subPackageNames = mutableSetOf<String>()
    val javaClassNameIndex = JavaClassNameIndex.getInstance(ktFile.project)

    javaClassNameIndex.subPackages(fqName, filter).forEach { name -> subPackageNames.add(name.asString()) }

    val declarationProvider = KotlinDeclarationProviderFactory.getInstance(ktFile.project)
        .createDeclarationProvider(analysisScope, useSiteModule)

    val allKotlinPackages = (declarationProvider.computePackageNamesWithTopLevelCallables()
        .orEmpty() + declarationProvider.computePackageNamesWithTopLevelClassifiers().orEmpty())

    val parentSegmentsSize = fqName.pathSegments().size
    allKotlinPackages.asSequence().map { FqName(it) }
        .filter { it != fqName && !it.isRoot && (fqName.isRoot || it.startsWith(fqName)) }
        .mapNotNull { it.pathSegments().getOrNull(parentSegmentsSize)?.asString() }
        .filter { filter(Name.identifier(it)) }.forEach { subPackageNames.add(it) }

    val packageCloseness = this@completePackage.closeness
    for (pkgName in subPackageNames) {
        add(declarationCompletionItem {
            name = pkgName
            show = pkgName
            insert = pkgName
            tag = CompletionItemTag.KEYWORD
            import = false
            this.closeness = packageCloseness
        })
    }


    if (!onlyPackages && !fqName.isRoot) {
        val packageSymbol = findPackage(fqName)
        val resolvedClassFqNames = mutableSetOf<String>()

        if (packageSymbol != null) {
            val classifiers = packageSymbol.packageScope.classifiers(filter)
            classifiers.forEach { classifier ->
                classifier.importableFqName?.asString()?.let(resolvedClassFqNames::add)
                add(classifier) { import = false }
            }

            val callables = packageSymbol.packageScope.callables(filter)
            add(callables) { import = false }
        }

        javaClassNameIndex.classesInPackage(fqName, filter)
            .filterNot { javaClass -> javaClass.fqName in resolvedClassFqNames }
            .forEach { javaClass -> addJavaClass(javaClass, import = false) }
    }
}

private fun CompletionItemsCollector.addJavaClass(javaClass: JavaClassName, import: Boolean) {
    val containerName = javaClass.fqName.substringBeforeLast('.', missingDelimiterValue = "")
    val classCloseness = if (import) CompletionCloseness.IMPORTED else this@addJavaClass.closeness
    add(declarationCompletionItem {
        show = javaClass.name.asString()
        insert = javaClass.name.asString()
        name = javaClass.name.asString()
        tag = CompletionItemTag.CLASS
        fqName = javaClass.fqName
        tail = containerName.takeIf(String::isNotEmpty)?.let { " ($it)" }
        this.import = import
        this.closeness = classCloseness
    })
}


context(kaSession: KaSession)
private fun CompletionItemsCollector.completeType(
    file: KtFile,
    element: KtElement,
    nameFilter: (Name) -> Boolean,
    annotationsOnly: Boolean,
) =
    with(kaSession) {
        phase(CompletionCloseness.SCOPE)
        for (scope in file.scopeContext(element).scopes) {
            add(scope.scope.classifiers(nameFilter))
        }
        phase(CompletionCloseness.IMPORTED)
        completeClassesWithImport(file, annotationsOnly)
    }

context(kaSession: KaSession)
private fun CompletionItemsCollector.completeNestedType(position: CompletionPosition.NestedType) = with(kaSession) {
    val classifiers = when (val symbol = position.receiver.referenceExpression?.mainReference?.resolveToSymbol()) {
        is KaTypeAliasSymbol -> symbol.expandedType.scope?.getClassifierSymbols(nameFilter)
        is KaDeclarationContainerSymbol -> symbol.combinedMemberScope.classifiers(nameFilter)
        // Поддержка вложенных типов через полное имя пакета в UserType
        is KaPackageSymbol -> {
            completePackage(position.receiver.containingKtFile, symbol.fqName, nameFilter, false)
            return
        }

        else -> null
    } ?: return
    add(classifiers)
}

context(_: KaSession)
private fun CompletionItemsCollector.completeTypeScope(scope: KaTypeScope) {
    add(scope.getCallableSignatures(nameFilter).filterShadowedJavaFieldsInSignatures())
    add(scope.getClassifierSymbols(nameFilter))
}

context(_: KaSession)
private fun CompletionItemsCollector.completeScope(scope: KaScope) {
    add(scope.callables(nameFilter).filterShadowedJavaFields())
    add(scope.classifiers(nameFilter))
}

context(_: KaSession)
internal fun getPosition(element: KtElement): CompletionPosition? {
    // Проверка, находимся ли мы в директиве package или import
    val importDirective = element.parentOfType<KtImportDirective>(withSelf = true)
    val packageDirective = element.parentOfType<KtPackageDirective>(withSelf = true)

    if (importDirective != null || packageDirective != null) {
        // Получаем текущий контекст квалификатора
        var current: PsiElement? = element
        while (current != null && current !is KtDotQualifiedExpression && current !is KtPackageDirective && current !is KtImportDirective) {
            current = current.parent
        }

        val prefix = if (element is KtSimpleNameExpression) element.getReferencedName()
            .removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER) else null

        val fqName = if (current is KtDotQualifiedExpression) {
            current.receiverExpression.text.let(::FqName)
        } else {
            FqName.ROOT
        }

        return CompletionPosition.Package(prefix, fqName, packageDirective != null)
    }

    return when (element) {
        is KtNameReferenceExpression -> {
            when (val parent = element.parent) {
                is KtDotQualifiedExpression if parent.selectorExpression == element -> getPosition(parent)
                is KtUserType if parent.referenceExpression == element -> getPositionByUserType(parent)
                is KtCallExpression -> {
                    val qualified = parent.parent as? KtQualifiedExpression
                    if (qualified?.selectorExpression == parent) getPosition(qualified)
                    else CompletionPosition.Identifier(element.getReferencedName().removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER))
                }
                else -> CompletionPosition.Identifier(
                    element.getReferencedName().removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER)
                )
            }
        }

        is KtQualifiedExpression -> {
            val selector = element.selectorExpression
            fun resolveName(element: KtElement?): String? {
                return when (element) {
                    is KtNameReferenceExpression -> element.getReferencedName()
                    is KtCallExpression -> resolveName(element.calleeExpression)
                    else -> null
                }
            }
            CompletionPosition.AfterDot(
                resolveName(selector)?.removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER),
                element.receiverExpression,
            )
        }

        is KtOperationReferenceExpression -> {
            val binaryExpression = element.parent as? KtBinaryExpression
            val left = binaryExpression?.left
            if (left != null) {
                CompletionPosition.AfterDot(
                    element.getReferencedName().removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER), left, true
                )
            } else null
        }

        is KtUserType -> getPositionByUserType(element)

        else -> null
    }
}

private fun Sequence<KaCallableSymbol>.filterShadowedJavaFields(): Sequence<KaCallableSymbol> = sequence {
    val bufferedJavaFields = mutableListOf<KaJavaFieldSymbol>()
    val syntheticPropertyNames = hashSetOf<Name>()

    for (symbol in this@filterShadowedJavaFields) {
        when (symbol) {
            is KaSyntheticJavaPropertySymbol -> {
                syntheticPropertyNames.add(symbol.name)
                yield(symbol)
            }

            is KaJavaFieldSymbol -> {
                bufferedJavaFields.add(symbol)
            }

            else -> {
                yield(symbol)
            }
        }
    }

    for (field in bufferedJavaFields) {
        if (field.name !in syntheticPropertyNames) {
            yield(field)
        }
    }
}

private fun Sequence<KaCallableSignature<*>>.filterShadowedJavaFieldsInSignatures(): Sequence<KaCallableSignature<*>> =
    sequence {
        val bufferedJavaFields = mutableListOf<KaCallableSignature<*>>()
        val syntheticPropertyNames = hashSetOf<Name>()

        for (signature in this@filterShadowedJavaFieldsInSignatures) {
            when (val symbol = signature.symbol) {
                is KaSyntheticJavaPropertySymbol -> {
                    syntheticPropertyNames.add(symbol.name)
                    yield(signature)
                }

                is KaJavaFieldSymbol -> {
                    bufferedJavaFields.add(signature)
                }

                else -> {
                    yield(signature)
                }
            }
        }

        for (fieldSignature in bufferedJavaFields) {
            if (fieldSignature.symbol.name !in syntheticPropertyNames) {
                yield(fieldSignature)
            }
        }
    }


private fun getPositionByUserType(element: KtUserType) = when (val qualifier = element.qualifier) {
    null -> CompletionPosition.Type(
        element.referencedName.orEmpty().removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER), element.isAnnotation()
    )

    else -> CompletionPosition.NestedType(
        element.referencedName.orEmpty().removeFakeIdentifier(COMPLETION_FAKE_IDENTIFIER),
        qualifier,
    )
}

private fun String.removeFakeIdentifier(suffix: String) =
    runCatching { substring(0, indexOf(suffix)) }.getOrElse { this }

fun KtUserType.isAnnotation(): Boolean {
    val typeReference = parent as? KtTypeReference ?: return false
    val constructor = typeReference.parent as? KtConstructorCalleeExpression ?: return false
    return constructor.parent is KtAnnotationEntry
}

internal sealed interface CompletionPosition {
    val prefix: String?

    class Identifier(override val prefix: String) : CompletionPosition

    class AfterDot(override val prefix: String?, val receiver: KtExpression, val isInfixContext: Boolean = false) :
        CompletionPosition {
        val nameExpression: KtSimpleNameExpression?
            get() = when (val parent = receiver.parent) {
                is KtQualifiedExpression -> when (val selector = parent.selectorExpression) {
                    is KtSimpleNameExpression -> selector
                    is KtCallExpression -> selector.getCallNameExpression()
                    else -> null
                }

                is KtBinaryExpression -> parent.operationReference
                else -> null
            }
    }

    class Type(override val prefix: String, val isAnnotation: Boolean) : CompletionPosition

    class NestedType(override val prefix: String?, val receiver: KtUserType) : CompletionPosition

    // Новая позиция для автодополнения пакетов (в import, package или при вводе полных путей)
    class Package(override val prefix: String?, val fqName: FqName, val onlyPackages: Boolean) : CompletionPosition
}
