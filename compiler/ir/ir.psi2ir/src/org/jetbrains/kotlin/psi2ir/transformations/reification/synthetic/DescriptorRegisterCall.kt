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
import org.jetbrains.kotlin.psi2ir.transformations.reification.registerResolvedCallForParameter
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
import sun.java2d.pipe.SpanShapeRenderer


class DescriptorRegisterCall(
    val project: Project,
    val clazz: LazyClassDescriptor,
    private val registerCall: KtCallExpression,
    val containingDeclaration: DeclarationDescriptor,
    val context: GeneratorContext,
    private val registerParamsArrayCall: (() -> Unit),
    private val registerAnnotationsArrayCall: (() -> Unit)
) {

    fun createCallDescriptor() {
        // register 'register' call
        registerResolvedCallDescriptionForFactoryMethod(
            (registerCall.parent as KtDotQualifiedExpression).operationTokenNode
        )
        // 1 argument pureCheck
        val lambdaExpression = PsiTreeUtil.findChildOfType(registerCall.valueArguments[0], KtLambdaExpression::class.java)
        val isExpression = PsiTreeUtil.findChildOfType(lambdaExpression, KtIsExpression::class.java)
        ReificationContext.register(
            lambdaExpression!!.bodyExpression!!.statements.last(),
            ReificationContext.ContextTypes.REIFICATION_CONTEXT,
            true
        )
        val lambdaReturnType = clazz.computeExternalType(createHiddenTypeReference(project, "Man"))
            .memberScope.findSingleFunction(Name.identifier("register")).valueParameters[0].type
        ReificationContext.register(lambdaExpression, ReificationContext.ContextTypes.TYPE, lambdaReturnType)
        val typeRef = clazz.computeExternalType(isExpression!!.typeReference)
        ReificationContext.register(isExpression, ReificationContext.ContextTypes.TYPE, typeRef)

        val lambdaSource = lambdaExpression.functionLiteral
        val lambdaDescriptor = AnonymousFunctionDescriptor(
            containingDeclaration,
            Annotations.EMPTY,
            CallableMemberDescriptor.Kind.DECLARATION,
            KotlinSourceElement(lambdaSource as KtElement),
            false
        )

        clazz.initializeLambdaDescriptor(
            containingDeclaration,
            lambdaSource,
            lambdaDescriptor,
            lambdaReturnType,
            DelegatingBindingTrace(BindingContext.EMPTY, "")
        )
        lambdaDescriptor.setReturnType(lambdaReturnType.arguments.last().type)
        ReificationContext.register(lambdaSource, ReificationContext.ContextTypes.DESC, lambdaDescriptor)
        registerResolvedCallForParameter(
            isExpression.leftHandSide as KtNameReferenceExpression,
            lambdaDescriptor.valueParameters[0]
        )
        //val param =
        //    if (containingDeclaration is SimpleFunctionDescriptor && containingDeclaration.name.identifier == "createTD") containingDeclaration.valueParameters.first() else null
        //registerFatherDescriptor(param)
        //2 argument KClass
        registerReflectionReference(
            PsiTreeUtil.findChildOfType(registerCall.valueArguments[1], KtClassLiteralExpression::class.java)!!,
            typeRef
        )
        //3 argument parameters array
        registerParamsArrayCall.invoke()

        //4 argument annotations array
        registerAnnotationsArrayCall.invoke()

        //5 argument father desc for non-reified types
        if (registerCall.valueArguments.size > 4) {
            registerFatherDescForNonReifiedType()
            registerImplementedInterfacesForNonReifiedTypes()
            registerIsInterfaceFlag()
        }
    }

    private fun registerIsInterfaceFlag() {
        val argumentExpression = registerCall.valueArguments[6].getArgumentExpression() as KtConstantExpression
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
        val argumentExpression = registerCall.valueArguments[4].getArgumentExpression() as KtDotQualifiedExpression
        val callExpression = argumentExpression.selectorExpression as? KtCallExpression
        if (callExpression != null) {
            DescriptorRegisterCall(
                registerCall.project,
                clazz,
                callExpression,
                containingDeclaration,
                context,
                {
                    registerArrayOfResolvedCall(
                        clazz,
                        callExpression.valueArguments[2].getArgumentExpression() as KtCallExpression,
                        clazz.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla"))
                    )
                },
                {
                    registerArrayOfResolvedCall(
                        clazz,
                        callExpression.valueArguments[3].getArgumentExpression() as KtCallExpression,
                        context.builtIns.intType
                    )
                }
            ).createCallDescriptor()
        } else {
            registerAnyDescCall(argumentExpression)
        }
    }

    private fun registerImplementedInterfacesForNonReifiedTypes() {
        val interfacesExpression = registerCall.valueArguments[5].getArgumentExpression() as KtCallExpression
        registerArrayOfResolvedCall(
            clazz,
            interfacesExpression,
            clazz.computeExternalType(createHiddenTypeReference(interfacesExpression.project, "Cla"))
        )
        interfacesExpression.valueArguments.forEach {
            val callExpression = (it.getArgumentExpression() as KtDotQualifiedExpression).selectorExpression as KtCallExpression
            DescriptorRegisterCall(
                interfacesExpression.project,
                clazz,
                callExpression,
                containingDeclaration,
                context,
                {
                    registerArrayOfResolvedCall(
                        clazz,
                        callExpression.valueArguments[2].getArgumentExpression() as KtCallExpression,
                        clazz.computeExternalType(createHiddenTypeReference(interfacesExpression.project, "Cla"))
                    )
                },
                {
                    registerArrayOfResolvedCall(
                        clazz,
                        callExpression.valueArguments[3].getArgumentExpression() as KtCallExpression,
                        context.builtIns.intType
                    )
                }).createCallDescriptor()
        }
    }

    private fun registerReflectionReference(expression: KtClassLiteralExpression, type: KotlinType) {
        val ktArgument = expression.receiverExpression!!
        val lhs = DoubleColonLHS.Type(type, PossiblyBareType.type(type))
        ReificationContext.register(ktArgument, ReificationContext.ContextTypes.REFLECTION_REF, lhs)
        val returnType = clazz.computeExternalType(KtPsiFactory(project, false).createType("kotlin.reflect.KClass<${type.constructor}>"))
        ReificationContext.register(expression, ReificationContext.ContextTypes.TYPE, returnType)
    }

    private fun registerResolvedCallDescriptionForFactoryMethod(
        callExpressionNode: ASTNode
    ): ResolvedCall<out CallableDescriptor>? {

        val functionDescriptor = clazz.computeExternalType(createHiddenTypeReference(project, "Man"))
            .memberScope.findSingleFunction(Name.identifier("register"))
        val classReceiverReferenceExpression = KtNameReferenceExpression(ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
            rawAddChildren(ASTFactory.leaf(KtTokens.IDENTIFIER, "Man"))
        })
        val explicitReceiver = ClassQualifier(classReceiverReferenceExpression, functionDescriptor.containingDeclaration as ClassDescriptor)
        ReificationContext.register(
            classReceiverReferenceExpression,
            ReificationContext.ContextTypes.DESC,
            functionDescriptor.containingDeclaration as ClassDescriptor
        )
        val call = CallMaker.makeCall(registerCall, explicitReceiver, callExpressionNode, registerCall, registerCall.valueArguments)
        val resolutionCandidate = ResolutionCandidate.create(
            call, functionDescriptor, explicitReceiver.classValueReceiver, ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, null
        )
        val resolvedCall = ResolvedCallImpl.create(
            resolutionCandidate,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
        ReificationContext.register(registerCall, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
        return resolvedCall
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