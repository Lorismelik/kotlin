/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.types.KotlinType

object ReificationContext {
    private val contextTable: MutableMap<Any, MutableMap<ContextTypes, Any>> = mutableMapOf()

    fun getReificationResolvedCall(key : KtPureElement) : ResolvedCall<*>? =
        contextTable[key]?.get(ContextTypes.RESOLVED_CALL) as? ResolvedCall<*>

    fun getReificationClassDescriptor(key : KtPureElement) : ClassDescriptor? =
        contextTable[key]?.get(ContextTypes.DESC) as? ClassDescriptor?

    fun getReificationLambdaType(key: KtExpression): KotlinType? =
        contextTable[key]?.get(ContextTypes.TYPE) as? KotlinType

    fun getReificationLambdaDesc(key: KtFunctionLiteral): AnonymousFunctionDescriptor? =
        contextTable[key]?.get(ContextTypes.DESC) as? AnonymousFunctionDescriptor

    fun getReificationIsExpressionType(key: KtTypeReference?): KotlinType? {
        return key?.let{contextTable[key]?.get(ContextTypes.TYPE) as? KotlinType}
    }

    fun getReificationContext(key: Any?): Boolean? {
        return key?.let{contextTable[key]?.get(ContextTypes.REIFICATION_CONTEXT) as? Boolean}
    }

    fun register(element: Any, context: ContextTypes, reificationValue: Any) : Any {
        if (!contextTable.containsKey(element)) {
            contextTable[element] = mutableMapOf(context to reificationValue)
        } else {
            contextTable[element]!![context] = reificationValue;
        }
        return reificationValue;
    }
}

enum class ContextTypes {
    DESC,
    TYPE,
    RESOLVED_CALL,
    REIFICATION_CONTEXT
}

