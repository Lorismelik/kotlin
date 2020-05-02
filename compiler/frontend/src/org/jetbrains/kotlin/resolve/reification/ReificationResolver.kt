/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.reification

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.reification.ReificationContext.ContextTypes
import org.jetbrains.kotlin.resolve.reification.ReificationContext.getReificationContext
import org.jetbrains.kotlin.resolve.reification.ReificationContext.register
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter


object ReificationResolver {
    fun resolveConstructorParameter(containingClass: KtClassOrObject?): KtParameter? {
        return containingClass?.let {
            getReificationContext<KtParameter>(
                containingClass,
                ContextTypes.CTOR_PARAM
            ) ?: let {
                val param = KtPsiFactory(containingClass.project, false).createParameter("private val desc: kotlin.reification._D.Cla")
                register(
                    containingClass,
                    ContextTypes.CTOR_PARAM,
                    param
                ) as KtParameter
            }
        }
    }

    fun resolveLocalDescriptorUsage(type: KotlinType, owner: DeclarationDescriptor) {
        var scopeOwner: DeclarationDescriptor? = owner
        while (scopeOwner != null && scopeOwner !is ModuleDescriptor && scopeOwner !is PackageFragmentDescriptor) {
            if (scopeOwner is ClassDescriptor) {
                val isCacheOwner = type.arguments.filter { it.type.isTypeParameter() }
                    .all {
                        it.type.constructor.declarationDescriptor?.containingDeclaration == scopeOwner
                    }
                if (isCacheOwner) {
                    registerLocalDescriptorUsage(scopeOwner, type)
                    return
                }
            }
            scopeOwner = scopeOwner.containingDeclaration
        }
    }

    private fun registerLocalDescriptorUsage(cacheOwner: ClassDescriptor, type: KotlinType) {
        val localCache = getReificationContext<MutableList<KotlinType>?>(
            cacheOwner,
            ContextTypes.CACHE
        )
        if (localCache != null && !localCache.contains(type)) {
            localCache.add(type)
        } else {
            register(
                cacheOwner,
                ContextTypes.CACHE,
                mutableListOf(type)
            )
        }
    }
}