/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
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
import org.jetbrains.kotlin.types.SimpleType

class GetKClassOperation(
    val project: Project,
    val clazz: LazyClassDescriptor,
    val context: GeneratorContext,
    val type: SimpleType
) {
    fun createKClassGetter(): KtExpression {
        val index = defineIndex()
        val moduleDesc = context.moduleDescriptor
        val intType = context.builtIns.intType
        return KtPsiFactory(project, false).createExpression(
            "desc.p[$index].type"
        ).apply {
            val arrayAccessExpression = PsiTreeUtil.findChildOfType(
                this,
                KtArrayAccessExpression::class.java
            )!!
            registerArrayAccessCall(
                arrayAccessExpression, clazz
            )
            registerIntConstant(
                PsiTreeUtil.findChildOfType(
                    this,
                    KtConstantExpression::class.java
                )!!,
                moduleDesc,
                intType
            )
            registerParameterArrayCall(
                clazz,
                PsiTreeUtil.findChildOfType(
                    this,
                    KtDotQualifiedExpression::class.java
                )!!
            )
            org.jetbrains.kotlin.psi2ir.transformations.reification.registerDescriptorCall(
                clazz,
                PsiTreeUtil.findChildOfType(
                    this,
                    KtNameReferenceExpression::class.java
                )!!
            )

            registerReflectionTypeCall(this as KtDotQualifiedExpression)
        }
    }

    private fun registerReflectionTypeCall(expression: KtDotQualifiedExpression) {
        val typeRef = clazz.computeExternalType(createHiddenTypeReference(expression.project, "Cla"))
        val candidateDesc = typeRef
            .memberScope.getContributedDescriptors().firstOrNull { it.name.identifier == "type" }
        val receiver = ExpressionReceiver.create(
            expression.receiverExpression,
            typeRef,
            BindingContext.EMPTY
        )
        val call = CallMaker.makeCall(
            expression.receiverExpression,
            receiver,
            expression.operationTokenNode,
            expression.selectorExpression!!,
            emptyList()
        )

        val resolvedCall = ResolvedCallImpl(
            call,
            candidateDesc as CallableDescriptor,
            receiver,
            null,
            ExplicitReceiverKind.DISPATCH_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        resolvedCall.markCallAsCompleted()
        resolvedCall.setStatusToSuccess()
        ReificationContext.register(expression.selectorExpression!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }

    private fun defineIndex(): Int {
        return clazz.declaredReifiedTypeParameters.indexOfFirst { it.defaultType.hashCode() == type.hashCode() }
    }
}