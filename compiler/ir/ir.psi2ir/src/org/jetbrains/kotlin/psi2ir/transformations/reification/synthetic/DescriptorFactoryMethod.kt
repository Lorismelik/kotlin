/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement


// Factory Descriptor Creating
// Something like:
/*fun createTD(p: Array<_D>): _D {
    return kotlin.reification._D.Man.register({it is C<*>}, p )
}*/
class DescriptorFactoryMethodGenerator(val project: Project, val clazz: LazyClassDescriptor) {

    var lambdaExpression: KtLambdaExpression? = null
    var isExpression: KtIsExpression? = null
    var registerCall: KtCallExpression? = null
    var argumentReference: KtNameReferenceExpression? = null


    fun createByFactory() =
        KtPsiFactory(project, false).createFunction(
            "fun createTD(p: Array<kotlin.reification._D>): kotlin.reification._D { return kotlin.reification._D.Man.register({it is C<*>}, p) }"
        ).apply {
            registerCall = PsiTreeUtil.findChildOfType(this, KtCallExpression::class.java)
            registerResolvedCallDescriptionForFactoryMethod(
                clazz,
                (registerCall!!.parent as KtDotQualifiedExpression).operationTokenNode
            )
            val valueArgList = PsiTreeUtil.findChildOfType(this, KtValueArgumentList::class.java)
            lambdaExpression = PsiTreeUtil.findChildOfType(this, KtLambdaExpression::class.java)
            isExpression = PsiTreeUtil.findChildOfType(lambdaExpression, KtIsExpression::class.java)
            ReificationContext.register(
                lambdaExpression!!.bodyExpression!!.statements.last(),
                ReificationContext.ContextTypes.REIFICATION_CONTEXT,
                true
            )
            val lambdaType = clazz.computeExternalType(createHiddenTypeReference(project, "Man"))
                .memberScope.findSingleFunction(Name.identifier("register")).valueParameters[0].type
            ReificationContext.register(lambdaExpression!!, ReificationContext.ContextTypes.TYPE, lambdaType)
            val typeRef = clazz.computeExternalType(isExpression!!.typeReference)
            ReificationContext.register(isExpression!!, ReificationContext.ContextTypes.TYPE, typeRef)
            argumentReference = PsiTreeUtil.findChildOfType(valueArgList!!.arguments.last(), KtNameReferenceExpression::class.java)
        }

    fun generateDescriptorFactoryMethodIfNeeded(clazz: ClassDescriptorWithResolutionScopes) {
        if (clazz is LazyClassDescriptor) {
            if (ReificationContext.getReificationContext<KtNamedFunction?>(
                    clazz,
                    ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION
                ) == null
            ) {
                createByFactory()
                val expression = generateFactoryMethodForReifiedDescriptor(clazz)
                ReificationContext.register(clazz, ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION, expression)
                val desc = createFactoryMethodDescriptor(clazz, expression)
                ReificationContext.register(expression, ReificationContext.ContextTypes.DESC, desc)
            }
        }
    }

    fun createFactoryMethodDescriptor(
        clazz: LazyClassDescriptor,
        declaration: KtNamedFunction
    ): SimpleFunctionDescriptorImpl {
        val desc = SimpleFunctionDescriptorImpl.create(
            clazz, Annotations.EMPTY,
            Name.identifier("createTD"),
            CallableMemberDescriptor.Kind.DECLARATION,
            clazz.source
        )

        val returnType = clazz.computeExternalType(declaration.typeReference)
        val parameter = ValueParameterDescriptorImpl(
            desc, null, 0, Annotations.EMPTY, Name.identifier("p"),
            clazz.computeExternalType(declaration.valueParameters[0].typeReference),
            declaresDefaultValue = false,
            isCrossinline = false,
            isNoinline = false,
            varargElementType = null,
            source = SourceElement.NO_SOURCE
        )
        return desc.initialize(
            null,
            clazz.thisAsReceiverParameter,
            emptyList(),
            listOf(parameter),
            returnType,
            Modality.FINAL,
            Visibilities.PUBLIC
        ).also {
            registerParameterDescriptorArrayResolvedCallForFactoryMethod(
                desc.valueParameters.first()
            )
            val lambdaSource = lambdaExpression!!.functionLiteral
            val lambdaDescriptor = AnonymousFunctionDescriptor(
                it,
                Annotations.EMPTY,
                CallableMemberDescriptor.Kind.DECLARATION,
                KotlinSourceElement(lambdaSource as KtElement),
                false
            )
            val lambdaType = clazz.computeExternalType(createHiddenTypeReference(project, "Man"))
                .memberScope.findSingleFunction(Name.identifier("register")).valueParameters[0].type

            clazz.initializeLambdaDescriptor(
                it,
                lambdaSource,
                lambdaDescriptor,
                lambdaType,
                DelegatingBindingTrace(BindingContext.EMPTY, "")
            )
            lambdaDescriptor.setReturnType(lambdaType.arguments.last().type)
            ReificationContext.register(lambdaSource, ReificationContext.ContextTypes.DESC, lambdaDescriptor)
            registerResolvedCallForIsInstance(
                isExpression?.leftHandSide as KtNameReferenceExpression,
                lambdaDescriptor.valueParameters[0]
            )
        }
    }

    fun generateFactoryMethodForReifiedDescriptor(clazz: LazyClassDescriptor): KtNamedFunction {
        return createByFactory()
    }


    private fun createRegisteringClassDescriptorExpression(callExpression: CompositeElement, clazz: LazyClassDescriptor): CompositeElement {
        return ASTFactory.composite(KtNodeTypes.DOT_QUALIFIED_EXPRESSION).apply {
            rawAddChildren(createHiddenDotQualifiedExpression("Man"))
            rawAddChildren(ASTFactory.leaf(KtTokens.DOT, "."))
            rawAddChildren(callExpression)
        }.also {
            registerCall = it.findPsiChildByType(KtNodeTypes.CALL_EXPRESSION) as KtCallExpression
            registerResolvedCallDescriptionForFactoryMethod(
                clazz,
                it
            )
        }
    }


    private fun registerParameterDescriptorArrayResolvedCallForFactoryMethod(
        desc: ValueParameterDescriptor
    ) {
        val call = CallMaker.makeCall(argumentReference, null, null, argumentReference, emptyList())
        val resolvedCall = ResolvedCallImpl(
            call,
            desc,
            null,
            null,
            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ReificationContext.register(argumentReference!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }

    private fun registerResolvedCallDescriptionForFactoryMethod(
        clazz: LazyClassDescriptor,
        callExpressionNode: ASTNode
    ): ResolvedCall<out CallableDescriptor>? {

        val functionDescriptor = clazz.computeExternalType(createHiddenTypeReference(project, "Man"))
            .memberScope.findSingleFunction(Name.identifier("register"))
        val classReceiverReferenceExpression = KtNameReferenceExpression(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
            rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "Man"))
        })
        val explicitReceiver = ClassQualifier(classReceiverReferenceExpression, functionDescriptor.containingDeclaration as ClassDescriptor)
        ReificationContext.register(
            classReceiverReferenceExpression,
            ReificationContext.ContextTypes.DESC,
            functionDescriptor.containingDeclaration as ClassDescriptor
        )
        val call = CallMaker.makeCall(registerCall, explicitReceiver, callExpressionNode, registerCall, registerCall!!.valueArguments)
        val resolutionCandidate = ResolutionCandidate.create(
            call, functionDescriptor, explicitReceiver.classValueReceiver, ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, null
        )
        val resolvedCall = ResolvedCallImpl.create(
            resolutionCandidate,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
        ReificationContext.register(registerCall!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
        return resolvedCall
    }

    private fun registerResolvedCallForIsInstance(referenceExpression: KtNameReferenceExpression, desc: ValueParameterDescriptor) {
        val call = CallMaker.makeCall(referenceExpression, null, null, referenceExpression, emptyList())
        val resolvedCall = ResolvedCallImpl(
            call,
            desc,
            null,
            null,
            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ReificationContext.register(referenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }
}
