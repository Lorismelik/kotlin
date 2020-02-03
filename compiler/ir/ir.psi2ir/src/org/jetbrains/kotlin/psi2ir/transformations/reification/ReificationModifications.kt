package org.jetbrains.kotlin.psi2ir.transformations.reification

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.impl.source.tree.TreeElement
import org.jetbrains.kotlin.KtNodeType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.TemporaryBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.getEffectiveExpectedType
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.getEffectiveExpectedTypeForSingleArgument
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.lazy.descriptors.scopeForInitializerResolution
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.types.KotlinType

//TODO TE use KtPsiFactory
fun createHiddenTypeReference(typeName: String? = null): CompositeElement {
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
            rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
            rawAddChildren(
                ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                    rawAddChildren(secondPackage)
                }
            )
        })
        rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
        rawAddChildren(abstractDescriptor)
    }

    return if (typeName == null) ASTFactory.composite(KtNodeTypes.TYPE_REFERENCE).apply {
        rawAddChildren(prefix)
    }
    else ASTFactory.composite(KtNodeTypes.TYPE_REFERENCE).apply {
        rawAddChildren(ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
            rawAddChildren(prefix)
            rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
            rawAddChildren(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, typeName))
            })
        })
    }
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
fun LazyClassDescriptor.resolveParametricSupertype(): KotlinType {
    val parametricRef = KtTypeReference(createHiddenTypeReference("Parametric"))
    return this.computeExternalType(parametricRef)
}



