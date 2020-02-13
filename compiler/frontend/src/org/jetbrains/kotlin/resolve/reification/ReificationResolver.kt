/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.reification

import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.reification.ReificationContext.ContextTypes
import org.jetbrains.kotlin.resolve.reification.ReificationContext.getReificationContext
import org.jetbrains.kotlin.resolve.reification.ReificationContext.register


object ReificationResolver {
    fun resolveConstructorParameter(containingClass: KtClassOrObject): KtParameter {
        return getReificationContext<KtParameter>(
            containingClass,
            ContextTypes.CTOR_PARAM
        ) ?: let {
            val param = KtPsiFactory(containingClass.project, false).createParameter("val desc: _D.Cla")
            register(
                this,
                ContextTypes.CTOR_PARAM,
                param
            ) as KtParameter
        }
    }
}