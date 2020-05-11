/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.createHiddenTypeReference
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerNull
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerResolvedCallForParameter
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerManagerFunctionCall
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerSimpleType
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerReflectionReference
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerPureCheckLambda
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.PossiblyBareType
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassValueReceiver
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.kotlin.types.model.typeConstructor
import org.jetbrains.kotlin.types.typeUtil.getImmediateSuperclassNotAny
import org.jetbrains.kotlin.types.typeUtil.isInterface
import org.jetbrains.kotlin.types.typeUtil.supertypes
import sun.java2d.pipe.SpanShapeRenderer


class DescriptorRegisterCall(
    val project: Project,
    val clazz: LazyClassDescriptor,
    val typeRef: KotlinType,
    private val registerCall: KtCallExpression,
    val containingDeclaration: DeclarationDescriptor,
    val context: GeneratorContext,
    private val registerParamsArrayCall: (() -> Unit)
) {

    fun createCallDescriptor() {
        // register 'register' call
        registerManagerFunctionCall("register", registerCall, clazz, project)
        // 1 argument pureCheck
        if ( registerCall.valueArguments[0].getArgumentExpression() !is KtConstantExpression) {
            val lambdaExpression = PsiTreeUtil.findChildOfType(registerCall.valueArguments[0], KtLambdaExpression::class.java)
            registerPureCheckLambda(lambdaExpression!!, typeRef, containingDeclaration, clazz, project)
        } else {
            registerNull(context, registerCall.valueArguments[0].getArgumentExpression()!!)
        }
        //val param =
        //    if (containingDeclaration is SimpleFunctionDescriptor && containingDeclaration.name.identifier == "createTD") containingDeclaration.valueParameters.first() else null
        //registerFatherDescriptor(param)
        //2 argument KClass
        registerReflectionReference(
            PsiTreeUtil.findChildOfType(registerCall.valueArguments[1], KtClassLiteralExpression::class.java)!!,
            typeRef,
            clazz,
            project
        )
        //3 argument parameters array
        registerParamsArrayCall.invoke()

        //4 argument father desc for non-reified types
        if (registerCall.valueArguments.size > 3) {
            registerFatherDescForNonReifiedType()
            registerImplementedInterfacesForNonReifiedTypes()
            registerIsInterfaceFlag()
        }
    }

    private fun registerIsInterfaceFlag() {
        val argumentExpression = registerCall.valueArguments[5].getArgumentExpression() as KtConstantExpression
        val params = CompileTimeConstant.Parameters(true, true, false, false, false, false, false)
        val constant = BooleanValue(argumentExpression.text == "true")
        ReificationContext.register(
            argumentExpression,
            ReificationContext.ContextTypes.CONSTANT,
            TypedCompileTimeConstant(constant, context.moduleDescriptor, params)
        )
        ReificationContext.register(
            argumentExpression,
            ReificationContext.ContextTypes.TYPE,
            this.context.builtIns.booleanType
        )
    }

    private fun registerFatherDescForNonReifiedType() {
        val argumentExpression = registerCall.valueArguments[3].getArgumentExpression()
        if (argumentExpression is KtBinaryExpression) {
            val callExpression = (argumentExpression.right as KtDotQualifiedExpression).selectorExpression as KtCallExpression
            val superType = typeRef.getImmediateSuperclassNotAny() ?: context.builtIns.anyType
            val registerParameterExecutor = if (callExpression.valueArguments[2].getArgumentExpression() is KtCallExpression) {
                {
                    registerArrayOfResolvedCall(
                        clazz,
                        callExpression.valueArguments[2].getArgumentExpression() as KtCallExpression,
                        clazz.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla"))
                    )
                }
            } else {
                { registerNull(context, callExpression.valueArguments[2].getArgumentExpression()!!) }
            }
            registerSimpleType(argumentExpression, superType, clazz, containingDeclaration, {registerParameterExecutor.invoke()}, context, registerCall.project)
        } else {
            registerNull(context, argumentExpression!!)
        }
    }

    private fun registerImplementedInterfacesForNonReifiedTypes() {
        val interfacesExpression = registerCall.valueArguments[4].getArgumentExpression()
        if (interfacesExpression is KtCallExpression) {
            registerArrayOfResolvedCall(
                clazz,
                interfacesExpression,
                clazz.computeExternalType(createHiddenTypeReference(interfacesExpression.project, "Cla"))
            )
            val interfaces = this.typeRef.supertypes().filter { it.isInterface() }
            interfacesExpression.valueArguments.forEachIndexed { index, arg ->
                val callExpression = ((arg.getArgumentExpression() as KtBinaryExpression).right as KtDotQualifiedExpression).selectorExpression as KtCallExpression
                val executor = {
                    registerNull(
                        context,
                        callExpression.valueArguments[2].getArgumentExpression()!!
                    )
                }
                registerSimpleType(arg.getArgumentExpression() as KtBinaryExpression, interfaces[index], clazz, containingDeclaration, executor, context, project)
            }
        } else {
            registerNull(context, interfacesExpression!!)
        }
    }

    private fun registerAnyDescCall(argExpression: KtDotQualifiedExpression) {
        val nameReferenceExpression = argExpression.selectorExpression as KtNameReferenceExpression
        val manDescType = clazz.computeExternalType(createHiddenTypeReference(nameReferenceExpression.project, "Man"))
        val manDesc = manDescType.constructor.declarationDescriptor as ClassDescriptor
        val explicitReceiver = ClassQualifier(
            (argExpression.receiverExpression as KtDotQualifiedExpression).selectorExpression as KtNameReferenceExpression,
            manDesc
        )
        val call =
            CallMaker.makeCall(
                nameReferenceExpression,
                explicitReceiver,
                argExpression.operationTokenNode,
                nameReferenceExpression,
                emptyList()
            )

        ReificationContext.register(
            (argExpression.receiverExpression as KtDotQualifiedExpression).selectorExpression!!,
            ReificationContext.ContextTypes.DESC,
            manDesc
        )
        val candidate = manDescType.memberScope.getContributedDescriptors().first { it.name.identifier == "anyDesc" }
        val resolvedCall = ResolvedCallImpl(
            call,
            candidate as CallableDescriptor,
            ClassValueReceiver(explicitReceiver, manDescType),
            null,
            ExplicitReceiverKind.DISPATCH_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
        ReificationContext.register(nameReferenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }
}