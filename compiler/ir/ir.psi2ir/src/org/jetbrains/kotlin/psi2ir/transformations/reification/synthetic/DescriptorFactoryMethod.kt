/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import java.lang.StringBuilder


// Factory Descriptor Creating
// Something like:
/*fun createTD(p: Array<_D>): _D {
    return kotlin.reification._D.Man.register({it is C<*>}, p )
}*/
class DescriptorFactoryMethodGenerator(val project: Project, val clazz: LazyClassDescriptor) {

    var registerCall: KtCallExpression? = null
    var argumentReference: KtNameReferenceExpression? = null


    private fun createByFactory(): KtNamedFunction {
        val typeRef = createTextTypeReferenceWithStarProjection()
        return KtPsiFactory(project, false).createFunction(
            "fun createTD(p: Array<kotlin.reification._D>): kotlin.reification._D { return kotlin.reification._D.Man.register({it is $typeRef}, p) }"
        ).apply {
            registerCall = PsiTreeUtil.findChildOfType(this, KtCallExpression::class.java)
            val valueArgList = PsiTreeUtil.findChildOfType(this, KtValueArgumentList::class.java)
            argumentReference = PsiTreeUtil.findChildOfType(valueArgList!!.arguments.last(), KtNameReferenceExpression::class.java)
        }
    }

    private fun createTextTypeReferenceWithStarProjection(): String {
        val type = this.clazz.defaultType
        return buildString {
            append(type.constructor)
            if (type.arguments.isNotEmpty()) type.arguments.joinTo(this, separator = ", ", prefix = "<", postfix = ">") { "*" }
            if (type.isMarkedNullable) append("?")
        }
    }

    fun generateDescriptorFactoryMethodIfNeeded(clazz: ClassDescriptorWithResolutionScopes) {
        if (ReificationContext.getReificationContext<KtNamedFunction?>(
                clazz,
                ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION
            ) == null
        ) {
            val expression = createByFactory()
            ReificationContext.register(clazz, ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION, expression)
            val desc = createFactoryMethodDescriptor(clazz, expression)
            ReificationContext.register(expression, ReificationContext.ContextTypes.DESC, desc)
        }
    }

    fun createFactoryMethodDescriptor(
        clazzCompanion: ClassDescriptor,
        declaration: KtNamedFunction
    ): SimpleFunctionDescriptorImpl {
        val desc = SimpleFunctionDescriptorImpl.create(
            clazzCompanion, Annotations.EMPTY,
            Name.identifier("createTD"),
            CallableMemberDescriptor.Kind.DECLARATION,
            clazzCompanion.source
        )

        val returnType = clazz.computeExternalType(declaration.typeReference)
        val parameter = ValueParameterDescriptorImpl(
            desc, null, 0, Annotations.EMPTY, Name.identifier("p"),
            clazz.computeExternalType(declaration.valueParameters[0].typeReference),
            declaresDefaultValue = false,
            isCrossinline = false,
            isNoinline = false,
            varargElementType = null,
            source = SourceElement.NO_SOURCE
        )
        return desc.initialize(
            null,
            clazzCompanion.thisAsReceiverParameter,
            emptyList(),
            listOf(parameter),
            returnType,
            Modality.FINAL,
            Visibilities.PUBLIC
        ).also {

            DescriptorRegisterCall(project, clazz, registerCall!!, it) {
                registerParameterDescriptorArrayResolvedCallForFactoryMethod(
                    desc.valueParameters.first()
                )
            }.createCallDescriptor()
        }
    }

    fun generateFactoryMethodForReifiedDescriptor(): KtNamedFunction {
        return createByFactory()
    }

    private fun registerParameterDescriptorArrayResolvedCallForFactoryMethod(
        desc: ValueParameterDescriptor
    ) {
        val call = CallMaker.makeCall(argumentReference, null, null, argumentReference, emptyList())
        val resolvedCall = ResolvedCallImpl(
            call,
            desc,
            null,
            null,
            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ReificationContext.register(argumentReference!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }
}
