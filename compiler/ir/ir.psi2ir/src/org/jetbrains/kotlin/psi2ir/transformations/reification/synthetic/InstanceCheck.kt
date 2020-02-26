/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.referencedProperty
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion.ALL
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion.CLASSIFIERS
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.findPackage
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.Variance

fun createDescriptorInstanceCheck(
    isExpression: KtIsExpression,
    descIndex: Int,
    clazz: LazyClassDescriptor,
    moduleDesc: ModuleDescriptor,
    builtInIntType: KotlinType
): KtExpression {
    val leftSide = isExpression.leftHandSide.text
    return KtPsiFactory(isExpression.project, false).createExpression("desc.p[$descIndex].isInstance($leftSide)").apply {
        registerIsInstanceCall(this, clazz)
        registerArrayAccessCall(this, clazz)
        registerIndexConstant(
            PsiTreeUtil.findChildOfType(
                this,
                KtConstantExpression::class.java
            )!!,
            descIndex,
            moduleDesc,
            builtInIntType
        )
        registerParameterArrayCall(
            clazz,
            PsiTreeUtil.findChildOfType(
                this,
                KtDotQualifiedExpression::class.java
            )!!
        )
        registerDescriptorCall(clazz,
                               PsiTreeUtil.findChildOfType(
                                   this,
                                   KtNameReferenceExpression::class.java
                               )!!)
    }
}

private fun registerIndexConstant(expression: KtConstantExpression, index: Int, moduleDesc: ModuleDescriptor, builtInIntType: KotlinType) {
    val params = CompileTimeConstant.Parameters(true, true, false, false, false, false, false)
    ReificationContext.register(
        expression,
        ReificationContext.ContextTypes.CONSTANT,
        IntegerValueTypeConstant(index, moduleDesc, params, false)
    )
    ReificationContext.register(
        expression,
        ReificationContext.ContextTypes.TYPE,
        builtInIntType
    )
}

private fun registerIsInstanceCall(expression: KtExpression, clazz: LazyClassDescriptor) {
    val candidateDesc = clazz.computeExternalType(createHiddenTypeReference(expression.project))
        .memberScope.findSingleFunction(Name.identifier("isInstance"))
    val receiver = ExpressionReceiver.create(
        PsiTreeUtil.findChildOfType(
            expression,
            KtArrayAccessExpression::class.java
        )!!,
        clazz.computeExternalType(createHiddenTypeReference(expression.project)),
        BindingContext.EMPTY
    )
    val isInstanceCallExpression = PsiTreeUtil.findChildOfType(
        expression,
        KtCallExpression::class.java
    )!!
    val call = CallMaker.makeCall(
        isInstanceCallExpression,
        receiver,
        (isInstanceCallExpression.parent as KtDotQualifiedExpression).operationTokenNode,
        isInstanceCallExpression.calleeExpression,
        isInstanceCallExpression.valueArguments
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidateDesc,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToReificationSuccess()
    ReificationContext.register(isInstanceCallExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

private fun registerArrayAccessCall(expression: KtExpression, clazz: LazyClassDescriptor) {
    val arrayAccessExpression = PsiTreeUtil.findChildOfType(
        expression,
        KtArrayAccessExpression::class.java
    )!!
    val candidate = getArrayGetDescriptor(
        clazz,
        arrayAccessExpression
    )
    val type = clazz.computeExternalType(KtPsiFactory(expression.project, false).createType("Array<_D>"))
    val receiver = ExpressionReceiver.create(
        arrayAccessExpression.arrayExpression!!,
        type,
        BindingContext.EMPTY
    )

    val call = CallMaker.makeCallWithExpressions(
        arrayAccessExpression,
        receiver,
        null,
        arrayAccessExpression,
        arrayAccessExpression.indexExpressions,
        Call.CallType.ARRAY_GET_METHOD
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidate,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToSuccess()
    ReificationContext.register(arrayAccessExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

private fun registerParameterArrayCall(
    clazz: LazyClassDescriptor,
    expression: KtDotQualifiedExpression
) {
    val descRef = expression.receiverExpression as KtNameReferenceExpression
    val referenceExpression = expression.selectorExpression!! as KtNameReferenceExpression
    val descType = clazz.computeExternalType(createHiddenTypeReference(referenceExpression.project, "Cla"))
    val receiver = ExpressionReceiver.create(
        descRef,
        descType,
        BindingContext.EMPTY
    )
    val call = CallMaker.makeCall(
        referenceExpression,
        receiver,
        expression.operationTokenNode,
        referenceExpression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    //TODO TE WTF!!?
    val lol = descType.memberScope.getContributedDescriptors().filter { it.name.identifier.equals("p") }.firstOrNull()
    val resolvedCall = ResolvedCallImpl(
        call,
        lol as CallableDescriptor,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ReificationContext.register(referenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

private fun registerDescriptorCall(clazz: LazyClassDescriptor, expression: KtNameReferenceExpression) {
    val dispatchReceiver = ImplicitClassReceiver(clazz)
    val candidate = clazz.declaredCallableMembers.first { x -> x.name.identifier.equals("desc") }
    val call = CallMaker.makeCall(
        expression,
        null,
        null,
        expression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidate,
        dispatchReceiver,
        null,
        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToSuccess()
    ReificationContext.register(expression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

fun getArrayGetDescriptor(descriptor: LazyClassDescriptor, element: KtArrayAccessExpression): DeserializedSimpleFunctionDescriptor {
    return (descriptor.scopeForClassHeaderResolution.findPackage(Name.identifier("kotlin"))!!.memberScope.getContributedDescriptors(
        CLASSIFIERS
    ) { x ->
        x.identifier.equals(
            "Array",
            true
        )
    }.first() as DeserializedClassDescriptor)
        .getMemberScope(
            listOf(
                TypeProjectionImpl(
                    Variance.INVARIANT,
                    descriptor.computeExternalType(createHiddenTypeReference(element.project))
                )
            )
        ).getContributedFunctions(
            Name.identifier("get"), KotlinLookupLocation(element)
        ).first() as DeserializedSimpleFunctionDescriptor
}
