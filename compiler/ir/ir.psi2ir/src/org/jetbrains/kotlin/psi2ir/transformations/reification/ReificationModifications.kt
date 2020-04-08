package org.jetbrains.kotlin.psi2ir.transformations.reification

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
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
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter

fun createHiddenTypeReference(project: Project, typeName: String? = null): KtTypeReference {
    val type = if (typeName != null) {
        "kotlin.reification._D.$typeName"
    } else "kotlin.reification._D"
    return KtPsiFactory(project, false).createTypeIfPossible(type)!!
}

fun createTypeParameterDescriptorSource(
    arg: KotlinType,
    callerTypeParams: List<TypeParameterDescriptor>,
    fromFactory: Boolean = false
): String {
    return buildString {
        append(
            when {
                arg.isTypeParameter() -> {
                    val index =
                        callerTypeParams.indexOfFirst { param -> param.defaultType.hashCode() == arg.hashCode() }
                    if (fromFactory) "p[$index]" else "desc.p[$index]"
                }
                (arg.constructor.declarationDescriptor as ClassDescriptor).isReified -> {
                    val classDesc = arg.constructor.declarationDescriptor as ClassDescriptor
                    createCodeForDescriptorFactoryMethodCall(
                        {
                            createTypeParametersDescriptorsSource(filterArgumentsForReifiedTypeParams(arg.arguments, classDesc.declaredTypeParameters), callerTypeParams, fromFactory)
                        },
                        {
                            createCodeForAnnotations(filterArgumentsForReifiedTypeParams(arg.arguments, classDesc.declaredTypeParameters), classDesc)
                        },
                        classDesc
                    )
                }
                else -> createSimpleTypeRegistrationSource(arg.asSimpleType())
            }
        )
    }
}

fun filterArgumentsForReifiedTypeParams(args: List<TypeProjection>, params: List<TypeParameterDescriptor>) : List<TypeProjection> {
    return args.filterIndexed { index, _ ->
        params[index].isReified }
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
    callerTypeParams: List<TypeParameterDescriptor> = emptyList()
): String {
    args.forEachIndexed { index, arg ->

    }
    return descriptor.declaredReifiedTypeParameters.map {
        it.variance.ordinal
    }.joinToString()
}

fun createTextTypeReferenceWithStarProjection(type: SimpleType): String {
    return buildString {
        append(type.constructor)
        if (type.arguments.isNotEmpty()) type.arguments.joinTo(this, separator = ", ", prefix = "<", postfix = ">") { "*" }
        if (type.isMarkedNullable) append("?")
    }
}

fun createSimpleTypeRegistrationSource(type: SimpleType): String {
    return buildString {
        val typeRef = createTextTypeReferenceWithStarProjection(type)
        append("kotlin.reification._D.Man.register({it is $typeRef}, ${type.constructor} :: class, arrayOf<kotlin.reification._D.Cla>(), arrayOf<Int>())")
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

//p[]
fun registerArrayAccessCall(arrayAccessExpression: KtArrayAccessExpression, clazz: LazyClassDescriptor) {
    val candidate = getArrayGetDescriptor(
        clazz,
        arrayAccessExpression
    )
    val type = clazz.computeExternalType(KtPsiFactory(arrayAccessExpression.project, false).createType("Array<_D.Cla>"))
    val receiver = ExpressionReceiver.create(
        arrayAccessExpression.arrayExpression!!,
        type,
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
fun registerParameterArrayCall(
    clazz: LazyClassDescriptor,
    expression: KtDotQualifiedExpression
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
    val lol = descType.memberScope.getContributedDescriptors().first { it.name.identifier == "p" }
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

fun getArrayGetDescriptor(descriptor: LazyClassDescriptor, element: KtArrayAccessExpression): DeserializedSimpleFunctionDescriptor {
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
                    descriptor.computeExternalType(createHiddenTypeReference(element.project, "Cla"))
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
