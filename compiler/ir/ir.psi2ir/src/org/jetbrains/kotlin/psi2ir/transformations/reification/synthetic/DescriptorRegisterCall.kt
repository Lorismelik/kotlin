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
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement


class DescriptorRegisterCall(
    val project: Project,
    val clazz: LazyClassDescriptor,
    private val registerCall: KtCallExpression,
    val containingDeclaration: DeclarationDescriptor,
    val context: GeneratorContext,
    private val registerArrayCall: (() -> Unit)? = null
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
        //2 argument father descriptor
        val param =
            if (containingDeclaration is SimpleFunctionDescriptor && containingDeclaration.name.identifier == "createTD") containingDeclaration.valueParameters.first() else null
        registerFatherDescriptor(param)
        //3 argument parameters array
        registerArrayCall?.invoke()
    }

    private fun registerFatherDescriptor(valueParameter: ValueParameterDescriptor?) {
        // father desc is second argument in call
        val fatherArgument = registerCall.valueArguments[1].getArgumentExpression()
        // father is null
        if (fatherArgument is KtConstantExpression) {
            val params = CompileTimeConstant.Parameters(false, false, false, false, false, false, false)
            val nullConstant = TypedCompileTimeConstant(NullValue(), context.moduleDescriptor, params)
            ReificationContext.register(fatherArgument, ReificationContext.ContextTypes.CONSTANT, nullConstant)
            ReificationContext.register(
                fatherArgument,
                ReificationContext.ContextTypes.TYPE,
                context.builtIns.nullableNothingType
            )
            // father is not null
        } else {
            registerDescriptorCreatingCall(
                clazz.getSuperClassOrAny() as LazyClassDescriptor,
                containingDeclaration,
                context,
                fatherArgument as KtDotQualifiedExpression,
                clazz,
                valueParameter
            )
        }
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
}