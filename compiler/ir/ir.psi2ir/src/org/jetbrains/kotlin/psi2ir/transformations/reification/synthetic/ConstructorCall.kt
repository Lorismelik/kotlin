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
import org.jetbrains.kotlin.resolve.reification.ReificationResolver
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassValueReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.findPackage
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import org.jetbrains.kotlin.types.*


fun createDescriptorArgument(
    descriptor: LazyClassDescriptor,
    containingDeclaration: DeclarationDescriptor,
    context: GeneratorContext,
    type : KotlinType,
    project: Project
): KtValueArgument {
    val args = type.arguments
    val classScope = ReificationResolver.findClassScopeIfExist(containingDeclaration)
    if (classScope != null) {
        val cache = ReificationContext.getReificationContext<List<KotlinType>?>(classScope, ReificationContext.ContextTypes.CACHE)
        if  (cache != null) {
            if (isStaticType(args)) {
                val cachedStaticTypes = cache.filter{ isStaticType(it.arguments)}
                return createCachedDescriptorForStaticType(cachedStaticTypes.indexOf(type), project, classScope, context)
            }
        }
    }
    return createNonCachedDescriptorArgument(descriptor, containingDeclaration, context, args, project)
}

fun createNonCachedDescriptorArgument(
    descriptor: LazyClassDescriptor,
    containingDeclaration: DeclarationDescriptor,
    context: GeneratorContext,
    args: List<TypeProjection>,
    project: Project
) : KtValueArgument {
    val originalDescriptor = findOriginalDescriptor(args)
    val filteredArgs = filterArgumentsForReifiedTypeParams(args, descriptor.declaredTypeParameters)
    val text = createCodeForDescriptorFactoryMethodCall(
        {
            createTypeParametersDescriptorsSource(
                filteredArgs,
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

fun createCachedDescriptorForStaticType(index: Int, project: Project, descriptor: ClassDescriptor, context: GeneratorContext) : KtValueArgument {
    return KtPsiFactory(project, false).createArgument("getStaticLocalDesc($index)").apply {

        val callExpression = this.getArgumentExpression() as KtCallExpression
        val funSource = LocalDescriptorCache(project, descriptor.companionObjectDescriptor!!, context ).generateLocalCacheGetterIfNeeded()
        val funDesc = ReificationContext.getReificationContext<FunctionDescriptor>(
            funSource,
            ReificationContext.ContextTypes.DESC
        )
        val call = CallMaker.makeCall(null, null, callExpression)
        val resolutionCandidate = ResolutionCandidate.create(
            call, funDesc as CallableDescriptor, ImplicitClassReceiver(descriptor.companionObjectDescriptor!!), ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, null
        )
        val resolvedCall = ResolvedCallImpl.create(
            resolutionCandidate,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
        ReificationContext.register(callExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
        resolvedCall.markCallAsCompleted()
        registerIntConstant(callExpression.valueArguments.first().getArgumentExpression() as KtConstantExpression, context.moduleDescriptor, context.builtIns.intType)
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
    originalDescriptorParamsArray: ValueParameterDescriptor? = null
) {
    val callExpression = expression.selectorExpression as KtCallExpression
    val parametersExpression = callExpression.valueArguments[0].getArgumentExpression()!!
    if (parametersExpression is KtCallExpression) {
        val arguments = parametersExpression.valueArgumentList
        registerParamsDescsCreating(
            arguments,
            descriptor,
            context,
            args,
            containingDeclaration,
            originalDescriptor,
            originalDescriptorParamsArray
        )
        registerArrayOfResolvedCall(
            descriptor,
            callExpression.valueArguments[0].getArgumentExpression() as KtCallExpression,
            descriptor.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla"))
        )
    } else {
        registerNull(context, parametersExpression)
    }
    val classReceiverReferenceExpression = expression.receiverExpression as KtNameReferenceExpression
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
    originalDescriptorParamsArray: ValueParameterDescriptor? = null
) {
    arguments?.arguments?.forEachIndexed { index, ktValueArg ->
        val argExpression = ktValueArg.getArgumentExpression()!!
        when {
            args[index].isStarProjection -> registerStarProjectionDescCall(argExpression as KtDotQualifiedExpression, descriptor)
            // Try to create desc for simple type
            argExpression is KtBinaryExpression -> {
                val executor = { registerNull(context, ((argExpression.right as KtDotQualifiedExpression).selectorExpression as KtCallExpression).valueArguments[2].getArgumentExpression()!!) }
                registerSimpleType(argExpression, args[index].type, descriptor, containingDeclaration, executor, context, arguments.project)
            }
            argExpression is KtDotQualifiedExpression -> {
                 val reifiedType = args[index].type
                 val reifiedTypeDesc = reifiedType.constructor.declarationDescriptor as LazyClassDescriptor
                 registerDescriptorCreatingCall(
                     reifiedTypeDesc,
                     filterArgumentsForReifiedTypeParams(reifiedType.arguments, reifiedTypeDesc.declaredTypeParameters),
                     containingDeclaration,
                     context,
                     argExpression,
                     originalDescriptor,
                     originalDescriptorParamsArray
                 )
            }
            argExpression is KtArrayAccessExpression -> {
                registerTemplateParametersOrAnnotations(
                    argExpression,
                    "kotlin.reification._D.Cla",
                    context,
                    originalDescriptor!!,
                    originalDescriptorParamsArray
                )
            }
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
        registerParameterOrAnnotationArrayCall(
            originalDescriptor,
            PsiTreeUtil.findChildOfType(
                argExpression,
                KtDotQualifiedExpression::class.java
            )!!
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


fun registerArrayOfResolvedCall(
    descriptor: LazyClassDescriptor,
    callExpression: KtCallExpression,
    substituteType: KotlinType,
    nulls: Boolean = false
) {
    val functionDescriptor = getArrayOfDescriptor(descriptor, nulls)
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

fun getArrayOfDescriptor(descriptor: LazyClassDescriptor, nulls: Boolean = false): DeserializedSimpleFunctionDescriptor {
    val name = if (nulls) "arrayOfNulls" else "arrayOf"
    val functions = descriptor.scopeForClassHeaderResolution.findPackage(Name.identifier("kotlin"))!!.memberScope.getContributedFunctions(
        Name.identifier(name), NoLookupLocation.FROM_BACKEND
    )
    return functions.first() as DeserializedSimpleFunctionDescriptor
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