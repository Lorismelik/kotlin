/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.synthetics.SyntheticClassOrObjectDescriptor
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.checkers.PrimitiveNumericComparisonInfo
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

class LocalDescriptorCache(val project: Project, val companionObject: ClassDescriptor, val context: GeneratorContext) {
    val clazz = companionObject.containingDeclaration as LazyClassDescriptor
    var openTypes : List<KotlinType>? = null
    var closedTypes : List<KotlinType>? = null

    private fun createPropertySource(): KtProperty {
        val types = ReificationContext.getReificationContext<List<KotlinType>>(
            clazz,
            ReificationContext.ContextTypes.CACHE
        )?.groupBy { it.arguments.any{x -> x.type.isTypeParameter()}}
        openTypes = types?.get(true)
        closedTypes = types?.get(false)
        val index = if (closedTypes != null) closedTypes!!.size else 0
        val text = "private val localDesc = arrayOfNull<kotlin.reification._D.Cla>($index)"
        return KtPsiFactory(project, false).createProperty(
            text
        ).apply {
            val source = this.toSourceElement()
            val propertyDescriptor = PropertyDescriptorImpl.create(
                companionObject,
                Annotations.EMPTY,
                Modality.FINAL,
                Visibilities.PRIVATE,
                false,
                Name.identifier("localDesc"),
                CallableMemberDescriptor.Kind.DECLARATION,
                source,
                false,
                false,
                false,
                false,
                false,
                false
            )

            val dispatchReceiverParameter = LazyClassReceiverParameterDescriptor(companionObject)
            val outType = clazz.computeExternalType(KtPsiFactory(project, false).createType("kotlin.Array<kotlin.reification._D.Cla?>"))
            propertyDescriptor.setType(outType, emptyList(), dispatchReceiverParameter, null)

            val backingField = FieldDescriptorImpl(Annotations.EMPTY, propertyDescriptor)
            val delegateField = FieldDescriptorImpl(Annotations.EMPTY, propertyDescriptor)
            val getter = PropertyGetterDescriptorImpl(
                propertyDescriptor,
                Annotations.EMPTY,
                Modality.FINAL,
                Visibilities.PRIVATE,
                true,
                false,
                false,
                CallableMemberDescriptor.Kind.DECLARATION,
                null,
                source
            )
            // maybe not
            getter.initialize(outType);
            propertyDescriptor.initialize(getter, null, backingField, delegateField)
            ReificationContext.register(this, ReificationContext.ContextTypes.DESC, propertyDescriptor)
            registerPropertyInit(this.initializer as KtCallExpression)
        }
    }

    private fun registerPropertyInit(callExpression: KtCallExpression) {
        registerArrayOfResolvedCall(
            clazz,
            callExpression,
            clazz.computeExternalType(createHiddenTypeReference(callExpression.project, "Cla?")),
            true
        )
        registerIntConstant(
            callExpression.valueArguments.first().getArgumentExpression()!! as KtConstantExpression,
            context.moduleDescriptor,
            context.builtIns.intType
        )
    }

    fun generateLocalCachePropertyIfNeeded(): KtProperty {
        return ReificationContext.getReificationContext(
            companionObject,
            ReificationContext.ContextTypes.LOCAL_CACHE_PROPERTY
        ) ?: createPropertySource().also {
            ReificationContext.register(companionObject, ReificationContext.ContextTypes.LOCAL_CACHE_PROPERTY, it)
        }
    }

    fun createStaticGetterSource(): KtNamedFunction {
        val switchText = generateSwitchSource()
        val text = """fun getStaticLocalDesc(pos: kotlin.Int) : kotlin.reification._D.Cla {
            |   if (localDesc[pos] != null) {
            |       return localDesc[pos]
            |   }
            |   localDesc[pos] = when(pos) {
            |       $switchText
            |       else -> null
            |   }
            |   return localDesc[pos]!!
            |}
        """.trimMargin()
        return KtPsiFactory(project, false).createFunction(
            text
        ).apply {
            registerStaticLocalCacheGetter(this)
        }
    }

    private fun registerStaticLocalCacheGetter(declaration : KtNamedFunction) {
        val desc = SimpleFunctionDescriptorImpl.create(
            companionObject, Annotations.EMPTY,
            Name.identifier(declaration.name!!),
            CallableMemberDescriptor.Kind.DECLARATION,
            companionObject.source
        )
        val returnType = clazz.computeExternalType(declaration.typeReference)
        val paramsDescsParameter = ValueParameterDescriptorImpl(
            desc, null, 0, Annotations.EMPTY, Name.identifier("pos"),
            // array descs of type params is 1 parameter
            clazz.computeExternalType(declaration.valueParameters[0].typeReference),
            declaresDefaultValue = false,
            isCrossinline = false,
            isNoinline = false,
            varargElementType = null,
            source = SourceElement.NO_SOURCE
        )
        desc.initialize(
            null,
            companionObject.thisAsReceiverParameter,
            emptyList(),
            listOf(paramsDescsParameter),
            returnType,
            Modality.FINAL,
            Visibilities.PUBLIC
        )
        registerStaticLocalCacheBody(declaration, desc)
        ReificationContext.register(declaration, ReificationContext.ContextTypes.DESC, desc)
    }

    private fun registerStaticLocalCacheBody(declaration: KtNamedFunction, desc: SimpleFunctionDescriptorImpl) {
        val statements = declaration.bodyBlockExpression!!.statements
        val ifExpression = statements[0] as KtIfExpression
        registerIfCondition(ifExpression, desc)
        registerIfBody(ifExpression, desc)
        registerLocalCacheAssignment(statements[1] as KtBinaryExpression, desc)
        val returnExpression = ((statements[2] as KtReturnExpression).returnedExpression as KtPostfixExpression)
        ReificationContext.register(returnExpression.baseExpression!!, ReificationContext.ContextTypes.TYPE, desc.returnType!!)
        registerLocalCacheArrayAccess(returnExpression.baseExpression as KtArrayAccessExpression, desc)
    }

    private fun registerIfCondition(ifExpression: KtIfExpression, desc: SimpleFunctionDescriptorImpl) {
        val condition = ifExpression.condition as KtBinaryExpression
        val arrayAccessExpression = condition.left as KtArrayAccessExpression
        registerLocalCacheArrayAccess(arrayAccessExpression, desc)
        registerNull(context, condition.right!!)
    }

    private fun registerIfBody(ifExpression: KtIfExpression, desc: SimpleFunctionDescriptorImpl) {
        val returnStatement = (ifExpression.then as KtBlockExpression).firstStatement as KtReturnExpression
        registerLocalCacheArrayAccess(returnStatement.returnedExpression as KtArrayAccessExpression, desc)
    }

    private fun registerLocalCacheAssignment(expression: KtBinaryExpression, desc: SimpleFunctionDescriptorImpl) {
        val whenExpression = expression.right as KtWhenExpression
        registerLocalCacheArrayAccess(expression.left as KtArrayAccessExpression, desc, false, whenExpression)
        // When subject
        registerResolvedCallForParameter(
            whenExpression.subjectExpression as KtNameReferenceExpression,
            desc.valueParameters[0]
        )
        // When type
        ReificationContext.register(whenExpression, ReificationContext.ContextTypes.TYPE, desc.returnType!!)
        // When entries
        whenExpression.entries.forEachIndexed { index, ktEntry ->
            if (!ktEntry.isElse) {
                val condition = (ktEntry.conditions.first() as KtWhenConditionWithExpression).expression as KtConstantExpression
                registerIntConstant(condition, context.moduleDescriptor, context.builtIns.intType)
                val comparisonInfo =
                    PrimitiveNumericComparisonInfo(context.builtIns.intType, context.builtIns.intType, context.builtIns.intType)
                ReificationContext.register(condition, ReificationContext.ContextTypes.PRIMITIVE_NUMERIC_COMPARISON_INFO, comparisonInfo)
                val descCreatingExpression = ktEntry.expression as KtDotQualifiedExpression
                val type = closedTypes!![index]
                val typeDesc = type.constructor.declarationDescriptor as LazyClassDescriptor
                registerDescriptorCreatingCall(
                    typeDesc,
                    filterArgumentsForReifiedTypeParams(type.arguments, typeDesc.declaredTypeParameters),
                    desc,
                    context,
                    descCreatingExpression
                )
            } else {
                registerNull(context, ktEntry.expression!!)
            }
        }
    }

    private fun registerLocalCacheArrayAccess(declaration: KtArrayAccessExpression, desc: SimpleFunctionDescriptorImpl, isGet : Boolean = true, valueExpression: KtExpression? = null) {
        registerArrayAccessCall(declaration, clazz, "kotlin.reification._D.Cla?", isGet, valueExpression)
        val parameterNameReferenceExpression = declaration.indexExpressions.first() as KtNameReferenceExpression
        registerResolvedCallForParameter(
            parameterNameReferenceExpression,
            desc.valueParameters[0]
        )
        registerStaticLocalCacheCall(declaration.arrayExpression as KtNameReferenceExpression)
    }

    fun registerStaticLocalCacheCall(descriptorCall : KtNameReferenceExpression) {
        val propertySource = ReificationContext.getReificationContext<KtProperty>(
            companionObject,
            ReificationContext.ContextTypes.LOCAL_CACHE_PROPERTY
        )
        val propertyDesc = ReificationContext.getReificationContext<PropertyDescriptorImpl>(
            propertySource,
            ReificationContext.ContextTypes.DESC
        )
        val call = CallMaker.makeCall(
            descriptorCall,
            null,
            null,
            descriptorCall,
            emptyList(),
            Call.CallType.DEFAULT,
            false
        )
        val resolvedCall = ResolvedCallImpl(
            call,
            propertyDesc as CallableDescriptor,
            ImplicitClassReceiver(companionObject),
            null,
            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        ReificationContext.register(
            descriptorCall,
            ReificationContext.ContextTypes.RESOLVED_CALL,
            resolvedCall
        )
        resolvedCall.markCallAsCompleted()
    }

    private fun generateSwitchSource(): String {
       return closedTypes!!.mapIndexed { index, type ->
           "$index -> ${createDescriptorRegisterCallSource(type)}"
       }.joinToString(separator = "\n")
    }

    private fun createDescriptorRegisterCallSource(type: KotlinType) : String {
        val descriptor = type.constructor.declarationDescriptor as ClassDescriptor
        val filteredArgs = filterArgumentsForReifiedTypeParams(type.arguments, descriptor.declaredTypeParameters)
        return createCodeForDescriptorFactoryMethodCall(
            {
                createTypeParametersDescriptorsSource(
                    filteredArgs,
                    emptyList()
                )
            },
            descriptor
        )
    }

    fun generateLocalCacheGetterIfNeeded(): KtNamedFunction {
        generateLocalCachePropertyIfNeeded()
        return ReificationContext.getReificationContext(
            companionObject,
            ReificationContext.ContextTypes.LOCAL_CACHE_STATIC_GETTER
        ) ?: createStaticGetterSource().also {
            ReificationContext.register(companionObject, ReificationContext.ContextTypes.LOCAL_CACHE_STATIC_GETTER, it)
        }
    }
}