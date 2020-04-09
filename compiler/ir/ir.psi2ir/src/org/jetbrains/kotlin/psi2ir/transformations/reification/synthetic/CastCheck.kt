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
import org.jetbrains.kotlin.types.SimpleType

class CastCheck(
    val expression: KtExpression,
    private val descIndex: Int,
    val clazz: LazyClassDescriptor,
    private val generatorContext: GeneratorContext
) {

    fun createCastToParamCheck(isSafe: Boolean, generatedExpression: IrExpression): KtExpression {
        val expressionText = this.expression.text
        val cast = if (isSafe) "safeCast" else "cast"
        return KtPsiFactory(expression.project, false).createExpression("desc.p[$descIndex].$cast($expressionText)").apply {
            val castCallExpression = (this as KtDotQualifiedExpression).selectorExpression!!
            val arrayAccessExpression = this.receiverExpression as KtArrayAccessExpression
            registerAccessToTypeParameter(
                arrayAccessExpression,
                clazz,
                generatorContext.moduleDescriptor,
                generatorContext.builtIns.intType
            )
            registerCastChecking(arrayAccessExpression, castCallExpression, generatedExpression, cast)

        }
    }

    private fun registerCastChecking(
        recieverExpression: KtExpression,
        callExpression: KtExpression,
        generatedExpression: IrExpression,
        cast: String
    ) {
        val argument = (callExpression.lastChild as KtValueArgumentList).arguments.first().getArgumentExpression()!!
        val castReciever = ExpressionReceiver.create(
            recieverExpression,
            clazz.computeExternalType(createHiddenTypeReference(recieverExpression.project, "Cla")),
            BindingContext.EMPTY
        )
        registerTypeOperationCall(callExpression as KtCallExpression, clazz, castReciever, cast)
        ReificationContext.register(
            argument, ReificationContext.ContextTypes.INSTANCE_OF_LEFT_IR, generatedExpression
        )
    }

    fun createCastToParamTypeCheck(
        isSafe: Boolean, generatedExpression: IrExpression, againstType: SimpleType
    ) {
        val leftSide = this.expression.text
        val clazz = againstType.constructor.declarationDescriptor as LazyClassDescriptor
        val originalDescriptor = findOriginalDescriptor(againstType.arguments)
        val text = buildString {
            append(
                createCodeForDescriptorFactoryMethodCall(
                    {
                        createTypeParametersDescriptorsSource(
                            filterArgumentsForReifiedTypeParams(
                                againstType.arguments,
                                clazz.declaredTypeParameters
                            ), originalDescriptor?.declaredReifiedTypeParameters ?: emptyList()
                        )
                    },
                    {
                        createCodeForAnnotations(
                            filterArgumentsForReifiedTypeParams(againstType.arguments, clazz.declaredTypeParameters),
                            clazz,
                            originalDescriptor?.declaredReifiedTypeParameters ?: emptyList()
                        )
                    },
                    clazz
                )
            )
            append(".isInstance($leftSide)")
        }
    }
}