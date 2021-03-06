/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.reification

object ReificationContext {
    private val contextTable: MutableMap<Any, MutableMap<ContextTypes, Any>> = mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    fun <T> getReificationContext(key: Any?, context: ContextTypes): T? {
        return key?.let { contextTable[key]?.get(context) as T? }
    }

    fun register(element: Any, context: ContextTypes, reificationValue: Any): Any {
        if (!contextTable.containsKey(element)) {
            contextTable[element] = mutableMapOf(context to reificationValue)
        } else {
            contextTable[element]!!.putIfAbsent(context, reificationValue)
        }
        return reificationValue;
    }

    enum class ContextTypes {
        CONSTANT,
        CTOR_PARAM,
        DESC,
        TYPE,
        RESOLVED_CALL,
        REIFICATION_CONTEXT,
        CONVERSION,
        REFLECTION_REF,
        DESC_FACTORY_EXPRESSION,
        ARRAY_OF,
        ARRAY_GET,
        INSTANCE_OF_LEFT_IR
    }
}