/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic.registerDescriptorCall
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType


fun createDescriptorInstanceCheck(
    isExpression: KtIsExpression,
    descIndex: Int,
    clazz: LazyClassDescriptor,
    moduleDesc: ModuleDescriptor,
    builtInIntType: KotlinType
): KtExpression {
    val leftSide = isExpression.leftHandSide.text
    return KtPsiFactory(isExpression.project, false).createExpression("desc.p[$descIndex].isInstance($leftSide)").apply {
        val arrayAccessExpression = (this as KtDotQualifiedExpression).receiverExpression as KtArrayAccessExpression
        val isInstanceCallReciever = ExpressionReceiver.create(
            arrayAccessExpression,
            clazz.computeExternalType(createHiddenTypeReference(this.project, "Cla")),
            BindingContext.EMPTY
        )
        val isInstanceCallExpression = PsiTreeUtil.findChildOfType(
            this,
            KtCallExpression::class.java
        )!!
        registerIsInstanceCall(isInstanceCallExpression, clazz, isInstanceCallReciever)
        registerArrayAccessCall(
            arrayAccessExpression, clazz
        )
        registerIntConstant(
            PsiTreeUtil.findChildOfType(
                arrayAccessExpression,
                KtConstantExpression::class.java
            )!!,
            moduleDesc,
            builtInIntType
        )
        registerParameterArrayCall(
            clazz,
            PsiTreeUtil.findChildOfType(
                arrayAccessExpression,
                KtDotQualifiedExpression::class.java
            )!!
        )
        registerDescriptorCall(
            clazz,
            PsiTreeUtil.findChildOfType(
                arrayAccessExpression,
                KtNameReferenceExpression::class.java
            )!!
        )
    }
}


fun createReifiedParamTypeInstanceCheck(
    isExpression: KtIsExpression,
    againstType: SimpleType,
    context: GeneratorContext,
    containingDeclaration: DeclarationDescriptor
): KtExpression {
    val leftSide = isExpression.leftHandSide.text
    val clazz = againstType.constructor.declarationDescriptor as LazyClassDescriptor
    val text = buildString {
        append(
            createCodeForDescriptorFactoryMethodCall(
                { createTypeParametersDescriptorsSource(againstType.arguments, emptyList()) },
                clazz
            )
        )
        append(".isInstance($leftSide)")
    }
    return KtPsiFactory(isExpression.project, false).createExpression(text)
        .apply {
            val createDescExpression = (this as KtDotQualifiedExpression).receiverExpression
            val isInstanceCall = this.selectorExpression!! as KtCallExpression
            val isInstanceCallReciever = ExpressionReceiver.create(
                PsiTreeUtil.findChildOfType(
                    this,
                    KtDotQualifiedExpression::class.java
                )!!,
                clazz.computeExternalType(createHiddenTypeReference(this.project, "Cla")),
                BindingContext.EMPTY
            )
            registerIsInstanceCall(isInstanceCall, clazz, isInstanceCallReciever)
            val originalDescriptor = findOriginalDescriptor(againstType.arguments)
            registerDescriptorCreatingCall(
                clazz,
                againstType.arguments,
                containingDeclaration,
                context,
                createDescExpression as KtDotQualifiedExpression,
                originalDescriptor
            )
        }
}

private fun registerIsInstanceCall(isInstanceCallExpression: KtCallExpression, clazz: LazyClassDescriptor, receiver: ExpressionReceiver) {
    val candidateDesc = clazz.computeExternalType(createHiddenTypeReference(isInstanceCallExpression.project, "Cla"))
        .memberScope.findSingleFunction(Name.identifier("isInstance"))
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


