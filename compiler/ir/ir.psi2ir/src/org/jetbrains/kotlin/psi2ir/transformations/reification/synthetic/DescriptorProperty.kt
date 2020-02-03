/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.impl.source.tree.TreeElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor


fun createValueParameterForConstructor(): TreeElement {
    return ASTFactory.composite(KtNodeTypes.VALUE_PARAMETER).apply {
        rawAddChildren(ASTFactory.leaf(KtTokens.VAL_KEYWORD, "val"))
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "desc"))
        rawAddChildren(ASTFactory.leaf(KtSingleValueToken("COLON", ":"), ":"))
        rawAddChildren(PsiWhiteSpaceImpl(" "))
        rawAddChildren(createHiddenTypeReference("Cla"))
    }
}

fun LazyClassDescriptor.createReifiedClassDescriptorAsValueParameter(
    constructorDescriptorImpl: ClassConstructorDescriptor
): ValueParameterDescriptorImpl {
    val parameter = KtParameter(createValueParameterForConstructor())
    val kotlinType = this.computeExternalType(KtTypeReference(createHiddenTypeReference("Cla")))
    return this.computeExternalValueParameter(
        constructorDescriptorImpl,
        parameter,
        constructorDescriptorImpl.valueParameters.size,
        kotlinType,
        Annotations.EMPTY
    )
}

fun LazyClassDescriptor.createReifiedClassDescriptorProperty(
    parameter: KtParameter
): PropertyDescriptor {
    return this.computeExternalProperty(
        parameter,
        this.createReifiedClassDescriptorAsValueParameter(parameter)
    )
}

fun LazyClassDescriptor.createReifiedClassDescriptorAsValueParameter(ktParameter: KtParameter): ValueParameterDescriptorImpl {
    val kotlinType = this.computeExternalType(KtTypeReference(createHiddenTypeReference("Cla")))
    val primaryConstructor = this.unsubstitutedPrimaryConstructor
    return this.computeExternalValueParameter(
        primaryConstructor,
        ktParameter,
        primaryConstructor?.valueParameters?.size ?: 0,
        kotlinType,
        Annotations.EMPTY
    )
}