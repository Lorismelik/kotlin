package org.jetbrains.kotlin.psi2ir.transformations.reification

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.impl.source.tree.TreeElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.scopes.LexicalScopeKind
import org.jetbrains.kotlin.resolve.scopes.LexicalWritableScope
import org.jetbrains.kotlin.resolve.scopes.TraceBasedLocalRedeclarationChecker
import org.jetbrains.kotlin.types.KotlinType

fun createHiddenTypeReference(typeName: String?): TreeElement {
    val firstPackage = ASTFactory.leaf(KtTokens.IDENTIFIER, "kotlin")
    val secondPackage = ASTFactory.leaf(KtTokens.IDENTIFIER, "reification")
    val abstractDescriptor = ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "_D"))
    }
    val prefix = ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
        rawAddChildren(ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
            rawAddChildren(
                ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
                    rawAddChildren(
                        ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                            rawAddChildren(firstPackage)
                        }
                    )
                }
            )
            rawAddChildren(ASTFactory.leaf(KtSingleValueToken("DOT", "."), "."))
            rawAddChildren(
                ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                    rawAddChildren(secondPackage)
                }
            )
        })
        rawAddChildren(ASTFactory.leaf(KtSingleValueToken("DOT", "."), "."))
        rawAddChildren(abstractDescriptor)
    }

    return if (typeName == null) ASTFactory.composite(KtNodeTypes.TYPE_REFERENCE).apply {
        rawAddChildren(prefix)
    }
    else ASTFactory.composite(KtNodeTypes.TYPE_REFERENCE).apply {
        rawAddChildren(ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
            rawAddChildren(prefix)
            rawAddChildren(ASTFactory.leaf(KtSingleValueToken("DOT", "."), "."))
            rawAddChildren(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, typeName))
            })
        })
    }
}

fun createValueParameter(): TreeElement {
    return ASTFactory.composite(KtNodeTypes.VALUE_PARAMETER).apply {
        rawAddChildren(ASTFactory.leaf(KtKeywordToken.keyword("val", "val"), "val"))
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "desc"))
        rawAddChildren(ASTFactory.leaf(KtSingleValueToken("COLON", ":"), ":"))
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(createHiddenTypeReference("Cla"))
    }
}

fun LazyClassDescriptor.resolveParametricSupertype(): KotlinType {
    val parametricRef = KtTypeReference(createHiddenTypeReference("Parametric"))
    return this.computeExternalSupertype(parametricRef)
}

fun LazyClassDescriptor.createReifiedClassDescriptorAsValueParameter(
    constructorDescriptorImpl: ClassConstructorDescriptor,
    index: Int
): ValueParameterDescriptorImpl {
    val parameter = KtParameter(createValueParameter())
    val kotlinType = this.computeExternalType(KtTypeReference(createHiddenTypeReference("Cla")))
    val annotations = Annotations.EMPTY
    return this.computeExternalValueParameter(constructorDescriptorImpl, parameter, index, kotlinType, annotations)
}
