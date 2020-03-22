/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor


fun createSuperCallArgument(
    resolvedCall: ResolvedCall<*>,
    descriptor: LazyClassDescriptor
): KtValueArgument {
    val text = "desc.father!!"
    return KtPsiFactory(resolvedCall.call.callElement.project, false).createArgument(text).apply {
        registerFatherDescriptorCallAndArgumentType(this, descriptor)
        registerDescriptorCall(this, descriptor)
    }
}

fun registerFatherDescriptorCallAndArgumentType(superCallArgument: KtValueArgument, descriptor: LazyClassDescriptor) {
    val fatherDescriptorCall = PsiTreeUtil.findChildOfType(superCallArgument, KtDotQualifiedExpression::class.java)
    val returnType = descriptor.computeExternalType(createHiddenTypeReference(superCallArgument.project, "Cla"))
    val explicitReceiver = ExpressionReceiver.create(
        fatherDescriptorCall!!.receiverExpression as KtNameReferenceExpression,
        returnType,
        BindingContext.EMPTY
    )
    val call = CallMaker.makeCall(
        fatherDescriptorCall.receiverExpression as KtNameReferenceExpression,
        explicitReceiver,
        fatherDescriptorCall.operationTokenNode,
        fatherDescriptorCall.selectorExpression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    val candidate =
        returnType.memberScope.getContributedDescriptors(DescriptorKindFilter.ALL) { x -> x.identifier == "father" }.first() as DeserializedPropertyDescriptor
    val fatherDescriptorResolvedCall = ResolvedCallImpl(
        call,
        candidate,
        explicitReceiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ReificationContext.register(
        fatherDescriptorCall.selectorExpression!!,
        ReificationContext.ContextTypes.RESOLVED_CALL,
        fatherDescriptorResolvedCall
    )
    ReificationContext.register(
        fatherDescriptorCall,
        ReificationContext.ContextTypes.TYPE,
        candidate.returnType
    )
    fatherDescriptorResolvedCall.markCallAsCompleted()
}

fun registerDescriptorCall(superCallArgument: KtValueArgument, clazzDescriptor: LazyClassDescriptor) {
    val descriptorCall = PsiTreeUtil.findChildOfType(superCallArgument, KtNameReferenceExpression::class.java)
    val call = CallMaker.makeCall(
        descriptorCall,
        null,
        null,
        descriptorCall,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    val candidate = clazzDescriptor.unsubstitutedPrimaryConstructor!!.valueParameters.last()
    val resolvedCall = ResolvedCallImpl(
        call,
        candidate,
        null,
        null,
        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ReificationContext.register(
        descriptorCall!!,
        ReificationContext.ContextTypes.RESOLVED_CALL,
        resolvedCall
    )
    resolvedCall.markCallAsCompleted()
}
