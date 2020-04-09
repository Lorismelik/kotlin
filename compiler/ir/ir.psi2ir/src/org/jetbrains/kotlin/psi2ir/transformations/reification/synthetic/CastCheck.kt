/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.expressions.IrExpression
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
import org.jetbrains.kotlin.types.KotlinType

class CastCheck(
    val expression: KtExpression,
    private val descIndex: Int,
    val clazz: LazyClassDescriptor,
    private val generatorContext: GeneratorContext
) {

    fun createInstanceCheck(isSafe: Boolean, generatedExpression: IrExpression): KtExpression {
        val expressionText = this.expression.text
        val cast = if (isSafe) "safeCast" else "cast"
        return KtPsiFactory(expression.project, false).createExpression("desc.p[$descIndex].$cast($expressionText)").apply {
            val newExpression = ((this as KtDotQualifiedExpression).selectorExpression!!.lastChild as KtValueArgumentList).arguments.first()
            val arrayAccessExpression = this.receiverExpression as KtArrayAccessExpression
            val castReciever = ExpressionReceiver.create(
                arrayAccessExpression,
                clazz.computeExternalType(createHiddenTypeReference(this.project, "Cla")),
                BindingContext.EMPTY
            )
            val isInstanceCallExpression = PsiTreeUtil.findChildOfType(
                this,
                KtCallExpression::class.java
            )!!
            registerCastCall(isInstanceCallExpression, clazz, castReciever, cast)
            registerAccessToTypeParameter(
                arrayAccessExpression,
                clazz,
                generatorContext.moduleDescriptor,
                generatorContext.builtIns.intType
            )
            ReificationContext.register(
                newExpression.getArgumentExpression()!!, ReificationContext.ContextTypes.INSTANCE_OF_LEFT_IR, generatedExpression
            )
        }
    }

    private fun registerCastCall(
        castCallExpression: KtCallExpression,
        clazz: LazyClassDescriptor,
        receiver: ExpressionReceiver,
        opName: String
    ) {
        val candidateDesc = clazz.computeExternalType(createHiddenTypeReference(castCallExpression.project, "Cla"))
            .memberScope.findSingleFunction(Name.identifier(opName))
        val call = CallMaker.makeCall(
            castCallExpression,
            receiver,
            (castCallExpression.parent as KtDotQualifiedExpression).operationTokenNode,
            castCallExpression.calleeExpression,
            castCallExpression.valueArguments
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
        ReificationContext.register(castCallExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }
}