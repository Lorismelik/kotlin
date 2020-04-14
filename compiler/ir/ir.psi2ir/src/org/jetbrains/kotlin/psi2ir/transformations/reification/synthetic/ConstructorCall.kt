/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.FunctionDescriptorUtil
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperInterfaces
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassValueReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.findPackage
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import org.jetbrains.kotlin.types.*


fun createDescriptorArgument(
    descriptor: LazyClassDescriptor,
    containingDeclaration: DeclarationDescriptor,
    context: GeneratorContext,
    args: List<TypeProjection>,
    project: Project
): KtValueArgument {
    val lol = getAllImplementedInterfaces(descriptor)
    val originalDescriptor = findOriginalDescriptor(args)
    val filteredArgs = filterArgumentsForReifiedTypeParams(args, descriptor.declaredTypeParameters)
    val text = createCodeForDescriptorFactoryMethodCall(
        {
            createTypeParametersDescriptorsSource(
                filteredArgs,
                originalDescriptor?.declaredReifiedTypeParameters ?: emptyList()
            )
        },
        {
            createCodeForAnnotations(
                filteredArgs,
                descriptor,
                originalDescriptor?.declaredReifiedTypeParameters ?: emptyList()
            )
        },
        descriptor
    )
    return KtPsiFactory(project, false).createArgument(text).apply {
        registerDescriptorCreatingCall(
            descriptor,
            filteredArgs,
            containingDeclaration,
            context,
            this.getArgumentExpression() as KtDotQualifiedExpression,
            originalDescriptor
        )
    }
}

fun registerDescriptorCreatingCall(
    descriptor: LazyClassDescriptor,
    args: List<TypeProjection>,
    containingDeclaration: DeclarationDescriptor,
    context: GeneratorContext,
    //ClassName.createTD(...)
    expression: KtDotQualifiedExpression,
    originalDescriptor: LazyClassDescriptor? = null,
    originalDescriptorParamsArray: ValueParameterDescriptor? = null,
    originalDescriptorAnnotationArray: ValueParameterDescriptor? = null
) {
    val arguments =
        ((expression.selectorExpression!! as KtCallExpression).valueArguments[0].getArgumentExpression() as KtCallExpression).valueArgumentList
    registerParamsDescsCreating(
        arguments,
        descriptor,
        context,
        args,
        containingDeclaration,
        originalDescriptor,
        originalDescriptorParamsArray,
        originalDescriptorAnnotationArray
    )
    val annotations =
        ((expression.selectorExpression!! as KtCallExpression).valueArguments[1].getArgumentExpression() as KtCallExpression).valueArgumentList
    registerAnnotations(annotations, context, originalDescriptor, originalDescriptorAnnotationArray)
    val callExpression = expression.selectorExpression as KtCallExpression
    val classReceiverReferenceExpression = expression.receiverExpression as KtNameReferenceExpression
    registerArrayOfResolvedCall(
        descriptor,
        callExpression.valueArguments[0].getArgumentExpression() as KtCallExpression,
        descriptor.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla"))
    )
    registerArrayOfResolvedCall(
        descriptor,
        callExpression.valueArguments[1].getArgumentExpression() as KtCallExpression,
        context.builtIns.intType
    )
    DescriptorFactoryMethodGenerator(
        expression.project,
        descriptor,
        context
    ).generateDescriptorFactoryMethodIfNeeded(descriptor.companionObjectDescriptor!!)
    val functionDescriptor = ReificationContext.getReificationContext<FunctionDescriptor?>(
        ReificationContext.getReificationContext(
            descriptor.companionObjectDescriptor!!,
            ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION
        ), ReificationContext.ContextTypes.DESC
    )
    val explicitReceiver = ClassQualifier(classReceiverReferenceExpression, descriptor)
    ReificationContext.register(
        classReceiverReferenceExpression,
        ReificationContext.ContextTypes.DESC,
        descriptor
    )
    val call = CallMaker.makeCall(
        callExpression,
        explicitReceiver,
        expression.operationTokenNode,
        callExpression,
        callExpression.valueArguments
    )
    val resolutionCandidate = ResolutionCandidate.create(
        call, functionDescriptor!!, explicitReceiver.classValueReceiver, ExplicitReceiverKind.DISPATCH_RECEIVER, null
    )
    val resolvedCallCopy = ResolvedCallImpl.create(
        resolutionCandidate,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCallCopy)
    ReificationContext.register(callExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCallCopy)
    resolvedCallCopy.markCallAsCompleted()
}

fun registerParamsDescsCreating(
    arguments: KtValueArgumentList?,
    descriptor: LazyClassDescriptor,
    context: GeneratorContext,
    args: List<TypeProjection>,
    containingDeclaration: DeclarationDescriptor,
    originalDescriptor: LazyClassDescriptor? = null,
    originalDescriptorParamsArray: ValueParameterDescriptor? = null,
    originalDescriptorAnnotationArray: ValueParameterDescriptor? = null
) {
    arguments?.arguments?.forEachIndexed { index, ktValueArg ->
        val argExpression = ktValueArg.getArgumentExpression()!!
        when {
            args[index].isStarProjection -> registerStarProjectionDescCall(argExpression as KtDotQualifiedExpression, descriptor)
            argExpression is KtDotQualifiedExpression -> {
                val callExpression = argExpression.selectorExpression!! as KtCallExpression
                // Try to create desc for reified type
                if (callExpression.calleeExpression!!.textMatches("createTD")) {
                    val reifiedType =
                        args.first { (it.type.constructor.declarationDescriptor as? ClassDescriptor)?.name?.identifier == argExpression.receiverExpression.text }
                            .type
                    val reifiedTypeDesc = reifiedType.constructor.declarationDescriptor as LazyClassDescriptor
                    registerDescriptorCreatingCall(
                        reifiedTypeDesc,
                        filterArgumentsForReifiedTypeParams(reifiedType.arguments, reifiedTypeDesc.declaredTypeParameters),
                        containingDeclaration,
                        context,
                        argExpression,
                        originalDescriptor,
                        originalDescriptorParamsArray,
                        originalDescriptorAnnotationArray
                    )
                    // Try to create desc for simple type
                } else {
                    DescriptorRegisterCall(
                        arguments.project,
                        descriptor,
                        callExpression,
                        containingDeclaration,
                        context,
                        {
                            registerArrayOfResolvedCall(
                                descriptor,
                                callExpression.valueArguments[2].getArgumentExpression() as KtCallExpression,
                                descriptor.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla"))
                            )
                        },
                        {
                            registerArrayOfResolvedCall(
                                descriptor,
                                callExpression.valueArguments[3].getArgumentExpression() as KtCallExpression,
                                context.builtIns.intType
                            )
                        }
                    ).createCallDescriptor()
                }
            }
            argExpression is KtArrayAccessExpression -> {
                registerTemplateParametersOrAnnotations(
                    argExpression,
                    "_D.Cla",
                    context,
                    originalDescriptor!!,
                    originalDescriptorParamsArray
                )
            }
        }
    }
}

fun registerAnnotations(
    annotationList: KtValueArgumentList?,
    context: GeneratorContext,
    originalDescriptor: LazyClassDescriptor? = null,
    originalDescriptorAnnotationArray: ValueParameterDescriptor? = null
) {
    annotationList?.arguments?.forEach { annotation ->
        val annotationExpression = annotation.getArgumentExpression()!!
        if (annotationExpression is KtConstantExpression) {
            registerIntConstant(
                annotationExpression,
                context.moduleDescriptor,
                context.builtIns.intType
            )
        } else {
            registerTemplateParametersOrAnnotations(
                annotationExpression as KtArrayAccessExpression,
                "kotlin.Int",
                context,
                originalDescriptor!!,
                originalDescriptorAnnotationArray
            )
        }
    }
}

fun registerTemplateParametersOrAnnotations(
    argExpression: KtArrayAccessExpression,
    arrayType: String,
    context: GeneratorContext,
    originalDescriptor: LazyClassDescriptor,
    originalDescriptorArray: ValueParameterDescriptor? = null
) {

    registerArrayAccessCall(
        argExpression, originalDescriptor, arrayType
    )

    val indexExpression = PsiTreeUtil.findChildOfType(
        argExpression,
        KtConstantExpression::class.java
    )!!

    registerIntConstant(
        indexExpression,
        context.moduleDescriptor,
        context.builtIns.intType
    )

    if (originalDescriptorArray == null) {
        // pass type parameter for another param reified type (desc.p[?])
        val isParameterArrayCall = arrayType == "_D.Cla"
        registerParameterOrAnnotationArrayCall(
            originalDescriptor,
            PsiTreeUtil.findChildOfType(
                argExpression,
                KtDotQualifiedExpression::class.java
            )!!,
            isParameterArrayCall
        )
        registerDescriptorCall(
            originalDescriptor,
            PsiTreeUtil.findChildOfType(
                argExpression,
                KtNameReferenceExpression::class.java
            )!!
        )
        // in createTD pass p parameter as argument for register fun
    } else {
        registerResolvedCallForParameter(
            PsiTreeUtil.findChildOfType(
                argExpression,
                KtNameReferenceExpression::class.java
            )!!, originalDescriptorArray
        )
    }
}

fun createTypeParametersDescriptorsSource(
    args: List<TypeProjection>,
    callerTypeParams: List<TypeParameterDescriptor>,
    fromFactory: Boolean = false
): String {
    return args.joinToString {
        createTypeParameterDescriptorSource(it, callerTypeParams, fromFactory)
    }
}


fun registerArrayOfResolvedCall(descriptor: LazyClassDescriptor, callExpression: KtCallExpression, substituteType: KotlinType) {
    val functionDescriptor = getArrayOfDescriptor(descriptor)
    val call = CallMaker.makeCall(
        callExpression,
        null,
        null,
        callExpression,
        callExpression.valueArguments
    )
    val resolutionCandidate = ResolutionCandidate.create(
        call, functionDescriptor, null, ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, null
    )
    val resolvedCall = ResolvedCallImpl.create(
        resolutionCandidate,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    val substitution = FunctionDescriptorUtil.createSubstitution(
        functionDescriptor,
        listOf(substituteType)
    )
    resolvedCall.setResultingSubstitutor(TypeSubstitutor.create(substitution))
    ReificationContext.register(callExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    resolvedCall.markCallAsCompleted()
}

fun getArrayOfDescriptor(descriptor: LazyClassDescriptor) =
    ReificationContext.getReificationContext(true, ReificationContext.ContextTypes.ARRAY_OF)
        ?: ReificationContext.register(
            true,
            ReificationContext.ContextTypes.ARRAY_OF,
            descriptor.scopeForClassHeaderResolution.findPackage(Name.identifier("kotlin"))!!.memberScope.getContributedFunctions(
                Name.identifier("arrayOf"), NoLookupLocation.FROM_BACKEND
            ).first()
        ) as DeserializedSimpleFunctionDescriptor

fun getAllImplementedInterfaces(descriptor: ClassDescriptor): HashSet<ClassDescriptor> {
    return with(hashSetOf<ClassDescriptor>()) {
        descriptor.getSuperInterfaces().also {
            it.forEach { superInterface ->
                this.add(superInterface)
                this.addAll(getAllImplementedInterfaces(superInterface))
            }
        }
        this
    }
}

fun registerStarProjectionDescCall(argExpression: KtDotQualifiedExpression, clazz: LazyClassDescriptor) {
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
    val candidate = manDescType.memberScope.getContributedDescriptors().first { it.name.identifier == "starProjection" }
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