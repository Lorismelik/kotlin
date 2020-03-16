/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic.registerDescriptorCall
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.FunctionDescriptorUtil
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
import org.jetbrains.kotlin.resolve.scopes.utils.findPackage
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.asSimpleType


fun createDescriptorArgument(
    resolvedCall: ResolvedCall<*>,
    descriptor: LazyClassDescriptor,
    scopeOwner: DeclarationDescriptor,
    context: GeneratorContext
): KtValueArgument {
    val expression = resolvedCall.call.callElement as KtCallExpression
    val text = createCodeForDescriptorFactoryMethodCall(
        { createTypeParametersDescriptorsSource((resolvedCall.resultingDescriptor as ClassConstructorDescriptorImpl).returnType.arguments) },
        descriptor
    )
    return KtPsiFactory(expression.project, false).createArgument(text).apply {
        registerDescriptorCreatingCall(descriptor, scopeOwner, context, this.getArgumentExpression() as KtDotQualifiedExpression)
    }
}

fun registerDescriptorCreatingCall(
    descriptor: LazyClassDescriptor,
    scopeOwner: DeclarationDescriptor,
    context: GeneratorContext,
    expression: KtDotQualifiedExpression,
    originalDescriptor: LazyClassDescriptor? = null,
    originalDescriptorParamsArray: ValueParameterDescriptor? = null
) {
    val arguments =
        PsiTreeUtil.findChildOfType(
            PsiTreeUtil.findChildOfType(expression, KtValueArgumentList::class.java),
            KtValueArgumentList::class.java
        )
    arguments?.arguments?.forEach {
        when (val argExpression = it.getArgumentExpression()!!) {
            is KtDotQualifiedExpression -> {
                DescriptorRegisterCall(
                    expression.project,
                    descriptor,
                    argExpression.selectorExpression!! as KtCallExpression,
                    scopeOwner,
                    context
                ) {
                    registerArrayOfResolvedCall(
                        descriptor,
                        (argExpression.selectorExpression!! as KtCallExpression).valueArguments.last().getArgumentExpression() as KtCallExpression,
                        expression.project
                    )
                }.createCallDescriptor()
            }
            is KtArrayAccessExpression -> {
                registerArrayAccessCall(
                    argExpression, originalDescriptor!!
                )
                val indexExpression = PsiTreeUtil.findChildOfType(
                    argExpression,
                    KtConstantExpression::class.java
                )!!
                registerIndexConstant(
                    indexExpression,
                    indexExpression.text.toInt(),
                    context.moduleDescriptor,
                    context.builtIns.intType
                )
                registerResolvedCallForParameter(
                    PsiTreeUtil.findChildOfType(
                        argExpression,
                        KtNameReferenceExpression::class.java
                    )!!, originalDescriptorParamsArray!!
                )
            }
        }

    }
    val callExpression = expression.selectorExpression as KtCallExpression
    val classReceiverReferenceExpression = KtNameReferenceExpression(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, descriptor.name.identifier))
    })
    registerArrayOfResolvedCall(
        descriptor,
        callExpression.valueArguments.first().getArgumentExpression() as KtCallExpression,
        expression.project
    )
    DescriptorFactoryMethodGenerator(
        expression.project,
        descriptor,
        context
    ).generateDescriptorFactoryMethodIfNeeded(descriptor.companionObjectDescriptor!!)
    val functionDescriptor = ReificationContext.getReificationContext<FunctionDescriptor?>(
        ReificationContext.getReificationContext(
            descriptor.companionObjectDescriptor!!,
            ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION
        ), ReificationContext.ContextTypes.DESC
    )
    val explicitReceiver = ClassQualifier(classReceiverReferenceExpression, descriptor)
    ReificationContext.register(
        classReceiverReferenceExpression,
        ReificationContext.ContextTypes.DESC,
        descriptor
    )
    val call = CallMaker.makeCall(
        callExpression,
        explicitReceiver,
        expression.operationTokenNode,
        callExpression,
        callExpression.valueArguments
    )
    val resolutionCandidate = ResolutionCandidate.create(
        call, functionDescriptor!!, explicitReceiver.classValueReceiver, ExplicitReceiverKind.DISPATCH_RECEIVER, null
    )
    val resolvedCallCopy = ResolvedCallImpl.create(
        resolutionCandidate,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCallCopy)
    ReificationContext.register(callExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCallCopy)
    resolvedCallCopy.markCallAsCompleted()
}

fun createTypeParametersDescriptorsSource(args: List<TypeProjection>): String {
    return args.map {
        with(StringBuilder()) {
            /*            if ((it.type.constructor.declarationDescriptor as ClassDescriptor).isReified) {
                append(
                    createCodeForDescriptorFactoryMethodCall(
                        { createTypeParametersDescriptorsSource(it.type.arguments) },
                        it.type.constructor.declarationDescriptor as ClassifierDescriptor
                    )
                )
            } else {*/
            append("kotlin.reification._D.Man.register({it is ")
            append(createTextTypeReferenceWithStarProjection(it.type.asSimpleType()))
            append("}, null, arrayOf<_D.Cla>())")
            //}
        }
    }.joinToString()
}

fun registerArrayOfResolvedCall(descriptor: LazyClassDescriptor, callExpression: KtCallExpression, project: Project) {
    val functionDescriptor = getArrayOfDescriptor(descriptor)
    val call = CallMaker.makeCall(
        callExpression,
        null,
        null,
        callExpression,
        callExpression.valueArguments
    )
    val resolutionCandidate = ResolutionCandidate.create(
        call, functionDescriptor, null, ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, null
    )
    val resolvedCall = ResolvedCallImpl.create(
        resolutionCandidate,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    val substitution = FunctionDescriptorUtil.createSubstitution(
        functionDescriptor,
        listOf(descriptor.computeExternalType(createHiddenTypeReference(project, "Cla")))
    )
    resolvedCall.setResultingSubstitutor(TypeSubstitutor.create(substitution))
    ReificationContext.register(callExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    resolvedCall.markCallAsCompleted()
}

fun getArrayOfDescriptor(descriptor: LazyClassDescriptor) =
    ReificationContext.getReificationContext(true, ReificationContext.ContextTypes.ARRAY_OF)
        ?: ReificationContext.register(
            true,
            ReificationContext.ContextTypes.ARRAY_OF,
            descriptor.scopeForClassHeaderResolution.findPackage(Name.identifier("kotlin"))!!.memberScope.getContributedFunctions(
                Name.identifier("arrayOf"), NoLookupLocation.FROM_BACKEND
            ).first()
        ) as DeserializedSimpleFunctionDescriptor