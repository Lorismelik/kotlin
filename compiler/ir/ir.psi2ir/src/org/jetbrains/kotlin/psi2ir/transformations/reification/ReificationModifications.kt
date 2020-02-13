package org.jetbrains.kotlin.psi2ir.transformations.reification

import com.intellij.lang.ASTFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.impl.source.tree.TreeElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.types.KotlinType

fun createHiddenTypeReference(project: Project, typeName: String? = null): KtTypeReference {
    val type = if (typeName != null) {
        "kotlin.reification._D.$typeName"
    } else "kotlin.reification._D"
    return KtPsiFactory(project, false).createTypeIfPossible(type)!!
}

fun createHiddenDotQualifiedExpression(typeName: String? = null): CompositeElement {
    val firstPackage = ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "kotlin"))
    }
    val secondPackage = ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "reification"))
    }
    val abstractDescriptor = ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "_D"))
    }

    val prefix = ASTFactory.composite(KtNodeTypes.DOT_QUALIFIED_EXPRESSION).apply {
        rawAddChildren(ASTFactory.composite(KtNodeTypes.DOT_QUALIFIED_EXPRESSION).apply {
            rawAddChildren(ASTFactory.composite(KtNodeTypes.DOT_QUALIFIED_EXPRESSION).apply {
                rawAddChildren(firstPackage)
            })
            rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
            rawAddChildren(secondPackage)
        })
        rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
        rawAddChildren(abstractDescriptor)
    }

    return if (typeName == null) prefix
    else ASTFactory.composite(KtNodeTypes.DOT_QUALIFIED_EXPRESSION).apply {
        rawAddChildren(prefix)
        rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
        rawAddChildren(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
            rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, typeName))
        })
    }
}


// Common Function
fun createFunction(
    funcName: String,
    returnType: CompositeElement,
    params: CompositeElement,
    body: KtBlockExpression
): CompositeElement {
    return ASTFactory.composite(KtNodeTypes.FUN).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.FUN_KEYWORD, "fun"))
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, funcName))
        rawAddChildren(params)
        rawAddChildren(ASTFactory.leaf(KtSingleValueToken("COLON", ":"), ":"))
        rawAddChildren(returnType)
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(body)
    }
}

fun createValueParamsList(
    params: List<CompositeElement>
): CompositeElement {
    return ASTFactory.composite(KtNodeTypes.VALUE_PARAMETER_LIST).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.LPAR, "("))
        params.forEachIndexed { index, param ->
            rawAddChildren(param)
            if (params.lastIndex != index) {
                rawAddChildren(ASTFactory.leaf(KtTokens.COMMA, ","))
                rawAddChildren(PsiWhiteSpaceImpl(" "))
            }
        }
        rawAddChildren(ASTFactory.leaf(KtTokens.RPAR, ")"))
    }
}

fun createValueArgumentList(
    args: List<TreeElement>
): CompositeElement {
    return ASTFactory.composite(KtNodeTypes.VALUE_ARGUMENT_LIST).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.LPAR, "("))
        args.forEachIndexed { index, arg ->
            rawAddChildren(arg)
            if (args.lastIndex != index) {
                rawAddChildren(ASTFactory.leaf(KtTokens.COMMA, ","))
                rawAddChildren(PsiWhiteSpaceImpl(" "))
            }
        }
        rawAddChildren(ASTFactory.leaf(KtTokens.RPAR, ")"))
    }
}


// Parametric super type
fun LazyClassDescriptor.resolveParametricSupertype(project: Project): KotlinType {
    val parametricRef = createHiddenTypeReference(project, "Parametric")
    return this.computeExternalType(parametricRef)
}



