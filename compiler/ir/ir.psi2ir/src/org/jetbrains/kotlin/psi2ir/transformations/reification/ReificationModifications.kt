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
                    createCodeForDescriptorFactoryMethodCall(
                        { createTypeParametersDescriptorsSource(arg.arguments, callerTypeParams, fromFactory) },
                        arg.constructor.declarationDescriptor as ClassifierDescriptor
                    )
                }
                else -> createSimpleTypeRegistrationSource(arg.asSimpleType())
            }
        )
    }
}

fun createCodeForDescriptorFactoryMethodCall(parametersDescriptors: () -> String, descriptor: ClassifierDescriptor): String {
    return "${descriptor.name.identifier}.createTD(arrayOf<_D.Cla>(${parametersDescriptors.invoke()}))"
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
        append("kotlin.reification._D.Man.register({it is $typeRef}, null, ${type.constructor} :: class, arrayOf<kotlin.reification._D.Cla>())")
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

// []
fun registerIndexConstant(expression: KtConstantExpression, index: Int, moduleDesc: ModuleDescriptor, builtInIntType: KotlinType) {
    val params = CompileTimeConstant.Parameters(true, true, false, false, false, false, false)
    ReificationContext.register(
        expression,
        ReificationContext.ContextTypes.CONSTANT,
        IntegerValueTypeConstant(index, moduleDesc, params, false)
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
    val lol = descType.memberScope.getContributedDescriptors().filter { it.name.identifier.equals("p") }.firstOrNull()
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
    ) { x ->
        x.identifier.equals(
            "Array",
            true
        )
    }.first() as DeserializedClassDescriptor)
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
