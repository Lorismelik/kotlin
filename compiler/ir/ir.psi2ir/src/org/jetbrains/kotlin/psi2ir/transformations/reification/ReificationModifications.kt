package org.jetbrains.kotlin.psi2ir.transformations.reification

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic.createTypeParametersDescriptorsSource
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.findPackage
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.*
import kotlin.reification._D

fun createHiddenTypeReference(project: Project, typeName: String? = null): KtTypeReference {
    val type = if (typeName != null) {
        "kotlin.reification._D.$typeName"
    } else "kotlin.reification._D"
    return KtPsiFactory(project, false).createTypeIfPossible(type)!!
}

fun createTypeParameterDescriptorSource(
    arg: TypeProjection,
    callerTypeParams: List<TypeParameterDescriptor>,
    fromFactory: Boolean = false
): String {
    return buildString {
        append(
            when {
                arg.isStarProjection -> "kotlin.reification._D.Man.starProjection"
                arg.type.isTypeParameter() -> {
                    val index =
                        callerTypeParams.indexOfFirst { param -> param.defaultType.hashCode() == arg.type.hashCode() }
                    if (fromFactory) "p[$index]" else "desc.p[$index]"
                }
                (arg.type.constructor.declarationDescriptor as ClassDescriptor).isReified -> {
                    val classDesc = arg.type.constructor.declarationDescriptor as ClassDescriptor
                    createCodeForDescriptorFactoryMethodCall(
                        {
                            createTypeParametersDescriptorsSource(
                                filterArgumentsForReifiedTypeParams(
                                    arg.type.arguments,
                                    classDesc.declaredTypeParameters
                                ), callerTypeParams, fromFactory
                            )
                        },
                        {
                            createCodeForAnnotations(
                                filterArgumentsForReifiedTypeParams(arg.type.arguments, classDesc.declaredTypeParameters),
                                classDesc, callerTypeParams, fromFactory
                            )
                        },
                        classDesc
                    )
                }
                else -> createSimpleTypeRegistrationSource(arg.type)
            }
        )
    }
}

fun filterArgumentsForReifiedTypeParams(args: List<TypeProjection>, params: List<TypeParameterDescriptor>): List<TypeProjection> {
    return args.filterIndexed { index, _ ->
        params[index].isReified
    }
}

fun createCodeForDescriptorFactoryMethodCall(
    parametersDescriptors: () -> String,
    annotations: () -> String,
    descriptor: ClassDescriptor
): String {
    return "${descriptor.name.identifier}.createTD(arrayOf<kotlin.reification._D.Cla>(${parametersDescriptors.invoke()}), arrayOf<Int>(${annotations.invoke()}))"
}

fun createCodeForAnnotations(
    args: List<TypeProjection>,
    descriptor: ClassDescriptor,
    callerTypeParams: List<TypeParameterDescriptor> = emptyList(),
    fromFactory: Boolean = false
): String {
    return args.mapIndexed<TypeProjection, Any> { index, arg ->
        if (arg.type.isTypeParameter()) {
            val annotationIndex =
                callerTypeParams.indexOfFirst { param -> param.defaultType.hashCode() == arg.type.hashCode() }
            return@mapIndexed if (fromFactory) "a[$annotationIndex]" else "desc.annotations[$annotationIndex]"
        }
        if (arg.isStarProjection) return@mapIndexed _D.Variance.BIVARIANT.ordinal
        return@mapIndexed mapVariance(descriptor.declaredReifiedTypeParameters[index].variance).ordinal
    }.joinToString()
}

fun mapVariance(compilerVariance: Variance): _D.Variance {
    return when (compilerVariance) {
        Variance.OUT_VARIANCE -> _D.Variance.OUT
        Variance.IN_VARIANCE -> _D.Variance.IN
        Variance.INVARIANT -> _D.Variance.INVARIANT
    }
}


fun createSimpleTypeRegistrationSource(type: KotlinType): String {
    return buildString {
        append(
            "kotlin.reification._D.Man.register(${type.constructor} :: class)"
        )
    }
}

//desc
fun registerDescriptorCall(clazz: LazyClassDescriptor, expression: KtNameReferenceExpression) {
    val dispatchReceiver = ImplicitClassReceiver(clazz)
    val candidate = clazz.declaredCallableMembers.first { x -> x.name.identifier == "desc" }
    val call = CallMaker.makeCall(
        expression,
        null,
        null,
        expression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidate,
        dispatchReceiver,
        null,
        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToSuccess()
    ReificationContext.register(expression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

//p[] or a[]
fun registerArrayAccessCall(arrayAccessExpression: KtArrayAccessExpression, clazz: LazyClassDescriptor, typeSource: String) {
    val candidate = getArrayGetDescriptor(
        clazz,
        arrayAccessExpression,
        typeSource
    )

    val receiver = ExpressionReceiver.create(
        arrayAccessExpression.arrayExpression!!,
        candidate.returnType!!,
        BindingContext.EMPTY
    )

    val call = CallMaker.makeCallWithExpressions(
        arrayAccessExpression,
        receiver,
        null,
        arrayAccessExpression,
        arrayAccessExpression.indexExpressions,
        Call.CallType.ARRAY_GET_METHOD
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidate,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToSuccess()
    ReificationContext.register(arrayAccessExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

// [?]
fun registerIntConstant(expression: KtConstantExpression, moduleDesc: ModuleDescriptor, builtInIntType: KotlinType) {
    val params = CompileTimeConstant.Parameters(true, true, false, false, false, false, false)
    ReificationContext.register(
        expression,
        ReificationContext.ContextTypes.CONSTANT,
        IntegerValueTypeConstant(expression.text.toInt(), moduleDesc, params, false)
    )
    ReificationContext.register(
        expression,
        ReificationContext.ContextTypes.TYPE,
        builtInIntType
    )
}

// call desc.p
fun registerParameterOrAnnotationArrayCall(
    clazz: LazyClassDescriptor,
    expression: KtDotQualifiedExpression,
    isParameterArray: Boolean = true
) {
    val descRef = expression.receiverExpression as KtNameReferenceExpression
    val referenceExpression = expression.selectorExpression!! as KtNameReferenceExpression
    val descType = clazz.computeExternalType(createHiddenTypeReference(referenceExpression.project, "Cla"))
    val receiver = ExpressionReceiver.create(
        descRef,
        descType,
        BindingContext.EMPTY
    )
    val call = CallMaker.makeCall(
        referenceExpression,
        receiver,
        expression.operationTokenNode,
        referenceExpression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    //TODO TE WTF!!?
    val propertyName = if (isParameterArray) "p" else "annotations"
    val lol = descType.memberScope.getContributedDescriptors().first { it.name.identifier == propertyName }
    val resolvedCall = ResolvedCallImpl(
        call,
        lol as CallableDescriptor,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ReificationContext.register(referenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

fun getArrayGetDescriptor(
    descriptor: LazyClassDescriptor,
    element: KtArrayAccessExpression,
    typeSource: String
): DeserializedSimpleFunctionDescriptor {
    return (descriptor.scopeForClassHeaderResolution.findPackage(Name.identifier("kotlin"))!!.memberScope.getContributedDescriptors(
        DescriptorKindFilter.CLASSIFIERS
    ).first { x ->
        x.name.identifier.equals(
            "Array",
            true
        )
    } as DeserializedClassDescriptor)
        .getMemberScope(
            listOf(
                TypeProjectionImpl(
                    Variance.INVARIANT,
                    descriptor.computeExternalType(KtPsiFactory(element.project, false).createType(typeSource))
                )
            )
        ).getContributedFunctions(
            Name.identifier("get"), KotlinLookupLocation(element)
        ).first() as DeserializedSimpleFunctionDescriptor
}

fun registerResolvedCallForParameter(referenceExpression: KtNameReferenceExpression, desc: ValueParameterDescriptor) {
    val call = CallMaker.makeCall(referenceExpression, null, null, referenceExpression, emptyList())
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
    ReificationContext.register(referenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

fun registerFatherCall(fatherCallExpression: KtDotQualifiedExpression, clazz: LazyClassDescriptor, project: Project) {
    val returnType = clazz.computeExternalType(createHiddenTypeReference(project, "Cla"))
    val explicitReceiver = ExpressionReceiver.create(
        fatherCallExpression.receiverExpression as KtNameReferenceExpression,
        returnType,
        BindingContext.EMPTY
    )
    val call = CallMaker.makeCall(
        fatherCallExpression.receiverExpression as KtNameReferenceExpression,
        explicitReceiver,
        fatherCallExpression.operationTokenNode,
        fatherCallExpression.selectorExpression,
        emptyList(),
        Call.CallType.DEFAULT,
        false
    )
    val candidate =
        returnType.memberScope.getContributedDescriptors(DescriptorKindFilter.ALL).first { x -> x.name.identifier == "father" } as DeserializedPropertyDescriptor
    val fatherDescriptorResolvedCall = ResolvedCallImpl(
        call,
        candidate,
        explicitReceiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ReificationContext.register(
        fatherCallExpression.selectorExpression!!,
        ReificationContext.ContextTypes.RESOLVED_CALL,
        fatherDescriptorResolvedCall
    )
    ReificationContext.register(
        fatherCallExpression,
        ReificationContext.ContextTypes.TYPE,
        candidate.returnType
    )
    fatherDescriptorResolvedCall.markCallAsCompleted()
}


fun registerAccessToTypeParameter(
    arrayAccessExpression: KtArrayAccessExpression,
    clazz: LazyClassDescriptor,
    moduleDesc: ModuleDescriptor,
    builtInIntType: KotlinType
) {
    registerArrayAccessCall(
        arrayAccessExpression, clazz, "_D.Cla"
    )
    registerIntConstant(
        PsiTreeUtil.findChildOfType(
            arrayAccessExpression,
            KtConstantExpression::class.java
        )!!,
        moduleDesc,
        builtInIntType
    )
    registerParameterOrAnnotationArrayCall(
        clazz,
        PsiTreeUtil.findChildOfType(
            arrayAccessExpression,
            KtDotQualifiedExpression::class.java
        )!!
    )
    registerDescriptorCall(
        clazz,
        PsiTreeUtil.findChildOfType(
            arrayAccessExpression,
            KtNameReferenceExpression::class.java
        )!!
    )
}

fun registerTypeOperationCall(
    castCallExpression: KtCallExpression,
    clazz: LazyClassDescriptor,
    receiver: ExpressionReceiver,
    opName: String
) {
    val candidateDesc = clazz.computeExternalType(createHiddenTypeReference(castCallExpression.project, "Cla"))
        .memberScope.findSingleFunction(Name.identifier(opName))
    val call = CallMaker.makeCall(
        castCallExpression,
        receiver,
        (castCallExpression.parent as KtDotQualifiedExpression).operationTokenNode,
        castCallExpression.calleeExpression,
        castCallExpression.valueArguments
    )
    val resolvedCall = ResolvedCallImpl(
        call,
        candidateDesc,
        receiver,
        null,
        ExplicitReceiverKind.DISPATCH_RECEIVER,
        null,
        DelegatingBindingTrace(BindingContext.EMPTY, ""),
        TracingStrategy.EMPTY,
        DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
    )
    ValueArgumentsToParametersMapper.mapValueArgumentsToParameters(call, TracingStrategy.EMPTY, resolvedCall)
    resolvedCall.markCallAsCompleted()
    resolvedCall.setStatusToReificationSuccess()
    ReificationContext.register(castCallExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
}

fun findOriginalDescriptor(args: List<TypeProjection>): LazyClassDescriptor? {
    for (arg in args) {
        if (arg.type.isTypeParameter()) {
            return arg.type.constructor.declarationDescriptor!!.containingDeclaration as LazyClassDescriptor
        } else {
            val res = findOriginalDescriptor(arg.type.arguments)
            if (res != null) return res;
        }
    }
    return null;
}