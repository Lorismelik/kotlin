/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerDescriptorCall
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny

class DescriptorGetter(
    val project: Project,
    val clazz: LazyClassDescriptor,
    val context: GeneratorContext
) {

    fun createDescriptorGetter(): KtNamedFunction {
        return KtPsiFactory(project, false).createFunction(
            "fun getD(): kotlin.reflect._D.Cla { return desc }"
        ).apply {
            registerDescriptorGetter(this)
        }
    }

    private fun registerDescriptorGetter(func: KtNamedFunction) {
        val desc = SimpleFunctionDescriptorImpl.create(
            clazz, Annotations.EMPTY,
            Name.identifier("getD"),
            CallableMemberDescriptor.Kind.DECLARATION,
            clazz.source
        )

        val returnType = clazz.computeExternalType(func.typeReference)
        desc.initialize(
            null,
            clazz.thisAsReceiverParameter,
            emptyList(),
            listOf(),
            returnType,
            Modality.OPEN,
            Visibilities.PUBLIC
        )
        val overriddenMethodDesc =
            clazz.computeExternalType(createHiddenTypeReference(func.project, "Parametric")).memberScope.findSingleFunction(desc.name)

        desc.overriddenDescriptors = listOf(overriddenMethodDesc)
        registerDescriptorCall(clazz, PsiTreeUtil.findChildOfType(func.bodyExpression!!, KtNameReferenceExpression::class.java)!!)
        ReificationContext.register(func, ReificationContext.ContextTypes.DESC, desc)
    }
}