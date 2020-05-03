/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.FieldDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.LazyClassReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyGetterDescriptorImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.createCodeForAnnotations
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.psi2ir.transformations.reification.createTextTypeReferenceWithStarProjection
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerIntConstant
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.types.KotlinType

class LocalDescriptorCache(val project: Project, val clazz: LazyClassDescriptor, val context: GeneratorContext) {
    var typesToRegister: List<KotlinType>? = null
    private fun createPropertySource(): KtProperty {
        typesToRegister = ReificationContext.getReificationContext<List<KotlinType>>(
            clazz.containingDeclaration as ClassDescriptor,
            ReificationContext.ContextTypes.CACHE
        )
        val index = if (typesToRegister != null) typesToRegister!!.size else 0
        val text = "private val localDesc = arrayOfNull<kotlin.reification._D.Cla>($index)"
        return KtPsiFactory(project, false).createProperty(
            text
        ).apply {
            val source = this.toSourceElement()
            val propertyDescriptor = PropertyDescriptorImpl.create(
                clazz,
                Annotations.EMPTY,
                Modality.FINAL,
                Visibilities.PRIVATE,
                false,
                Name.identifier("localDesc"),
                CallableMemberDescriptor.Kind.DECLARATION,
                source,
                false,
                false,
                false,
                false,
                false,
                false
            )

            val dispatchReceiverParameter = LazyClassReceiverParameterDescriptor(clazz)
            val outType = clazz.computeExternalType(KtPsiFactory(project, false).createType("kotlin.Array<kotlin.reification._D.Cla?>"))
            propertyDescriptor.setType(outType, emptyList(), dispatchReceiverParameter, null)

            val backingField = FieldDescriptorImpl(Annotations.EMPTY, propertyDescriptor)
            val delegateField = FieldDescriptorImpl(Annotations.EMPTY, propertyDescriptor)
            val getter = PropertyGetterDescriptorImpl(
                propertyDescriptor,
                Annotations.EMPTY,
                Modality.FINAL,
                Visibilities.PRIVATE,
                true,
                false,
                false,
                CallableMemberDescriptor.Kind.DECLARATION,
                null,
                source
            )
            propertyDescriptor.initialize(getter, null, backingField, delegateField)
            ReificationContext.register(this, ReificationContext.ContextTypes.DESC, propertyDescriptor)
            registerPropertyInit(this.initializer as KtCallExpression)
        }
    }

    private fun registerPropertyInit(callExpression: KtCallExpression) {
        registerArrayOfResolvedCall(
            clazz,
            callExpression,
            clazz.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla?")),
            true
        )
        registerIntConstant(
            callExpression.valueArguments.first().getArgumentExpression()!! as KtConstantExpression,
            context.moduleDescriptor,
            context.builtIns.intType
        )
    }

    fun generateLocalCachePropertyIfNeeded(clazz: ClassDescriptor): KtProperty {
        return ReificationContext.getReificationContext(
            clazz,
            ReificationContext.ContextTypes.LOCAL_CACHE_PROPERTY
        ) ?: createPropertySource().also {
            ReificationContext.register(clazz, ReificationContext.ContextTypes.LOCAL_CACHE_PROPERTY, it)
        }
    }

/*    fun generateLocalCacheGetterIfNeeded(clazz: ClassDescriptor): KtNamedFunction {
        return ReificationContext.getReificationContext(
            clazz,
            ReificationContext.ContextTypes.LOCAL_CACHE_GETTER
        ) ?: createByFactory().also {
            ReificationContext.register(clazz, ReificationContext.ContextTypes.LOCAL_CACHE_GETTER, it)
            //createFactoryMethodDescriptor(clazz, it)
        }
    }*/
}