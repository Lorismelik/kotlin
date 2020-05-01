/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
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
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.getImmediateSuperclassNotAny
import org.jetbrains.kotlin.types.typeUtil.isInterface
import org.jetbrains.kotlin.types.typeUtil.supertypes
import java.lang.StringBuilder

class DescriptorFactoryMethodGenerator(val project: Project, val clazz: LazyClassDescriptor, val context: GeneratorContext) {

    var registerCall: KtCallExpression? = null
    var parameterArrayArgumentReference: KtNameReferenceExpression? = null
    var typeDescProperty: KtProperty? = null
    var descriptor: LocalVariableDescriptor? = null
    var supertypeAssignment: KtBinaryExpression? = null
    var returnExpression: KtNameReferenceExpression? = null
    var ifExpression: KtIfExpression? = null
    var interafacesAssignment: KtBinaryExpression? = null
    var implementedInterfaces: List<KotlinType>? = null
    var annotationsAssignment: KtBinaryExpression? = null

    private fun createByFactory(): KtNamedFunction {
        val typeRef = createTextTypeReferenceWithStarProjection(this.clazz.defaultType)
        val fatherDescriptor = fatherDescriptorRegisteringCode()
        val intsDescs = interfacesDescriptorsRegisteringCode()
        val annotations = createCodeForAnnotations(clazz)
        val newText = """fun createTD(p: Array<kotlin.reification._D.Cla>): kotlin.reification._D.Cla { 
                |   val typeDesc = kotlin.reification._D.Man.register({it is $typeRef}, ${this.clazz.defaultType.constructor} :: class, p)
                |   if (typeDesc.firstReg()) {
                |       typeDesc.father = $fatherDescriptor
                |       typeDesc.ints = arrayOf<kotlin.reification._D.Cla>($intsDescs)
                |       typeDesc.annotations = arrayOf<Int>($annotations)
                |   }
                |   return typeDesc
                |}""".trimMargin()
        return KtPsiFactory(project, false).createFunction(
            newText
        ).apply {
            val statements = this.bodyBlockExpression!!.statements
            typeDescProperty = statements[0] as KtProperty
            ifExpression = statements[1] as KtIfExpression
            supertypeAssignment = (ifExpression!!.then as KtBlockExpression).firstStatement as KtBinaryExpression
            interafacesAssignment = (ifExpression!!.then as KtBlockExpression).statements[1] as KtBinaryExpression
            annotationsAssignment = (ifExpression!!.then as KtBlockExpression).statements[2] as KtBinaryExpression
            returnExpression = PsiTreeUtil.findChildOfType(statements[2], KtNameReferenceExpression::class.java)
            registerCall = PsiTreeUtil.findChildOfType(statements[0], KtCallExpression::class.java)
            val valueArgList = PsiTreeUtil.findChildOfType(statements[0], KtValueArgumentList::class.java)
            val pureCheckExpression = PsiTreeUtil.findChildOfType(valueArgList!!.arguments[0], KtIsExpression::class.java)!!
            ReificationContext.register(pureCheckExpression, ReificationContext.ContextTypes.REIFICATION_CONTEXT, true)
            parameterArrayArgumentReference = PsiTreeUtil.findChildOfType(valueArgList.arguments[2], KtNameReferenceExpression::class.java)
        }
    }

    private fun interfacesDescriptorsRegisteringCode(): String {
        implementedInterfaces = this.clazz.defaultType.supertypes().filter { it.isInterface() }
        return implementedInterfaces!!.joinToString {
            createTypeParameterDescriptorSource(
                it.asTypeProjection(),
                clazz.declaredReifiedTypeParameters,
                true
            )
        }
    }

    private fun fatherDescriptorRegisteringCode(): String {
        val supertype = clazz.getSuperClassNotAny()
        return if (supertype == null || !supertype.isReified) "null"
        else {
            val childReifiedTypeParams = clazz.declaredReifiedTypeParameters
            // find arguments that link to reified params
            val reifiedTypeInstances =
                clazz.defaultType.constructor.supertypes.first()
                    .arguments.filterIndexed { index, _ ->
                    supertype.declaredTypeParameters[index].isReified
                }
            createCodeForDescriptorFactoryMethodCall(
                {
                    reifiedTypeInstances.map {
                        with(StringBuilder()) {
                            append(
                                createTypeParameterDescriptorSource(
                                    it,
                                    childReifiedTypeParams,
                                    true
                                )
                            )
                        }
                    }.joinToString()
                },
                supertype
            )
        }
    }

    fun generateDescriptorFactoryMethodIfNeeded(clazz: ClassDescriptor): KtNamedFunction {
        return ReificationContext.getReificationContext(
            clazz,
            ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION
        ) ?: createByFactory().also {
            ReificationContext.register(clazz, ReificationContext.ContextTypes.DESC_FACTORY_EXPRESSION, it)
            createFactoryMethodDescriptor(clazz, it)
        }
    }

    private fun createFactoryMethodDescriptor(
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
        val paramsDescsParameter = ValueParameterDescriptorImpl(
            desc, null, 0, Annotations.EMPTY, Name.identifier("p"),
            // array descs of type params is 1 parameter
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
            listOf(paramsDescsParameter),
            returnType,
            Modality.FINAL,
            Visibilities.PUBLIC
        ).also {
            registerDescVariable(it)
            DescriptorRegisterCall(
                project,
                clazz,
                clazz.defaultType,
                registerCall!!,
                it,
                context
            ) {
                registerResolvedCallForParameter(
                    parameterArrayArgumentReference!!,
                    it.valueParameters[0]
                )
            }.createCallDescriptor()

            registerIfCondition()
            ReificationContext.register(ifExpression!!.then!!, ReificationContext.ContextTypes.TYPE, this.context.builtIns.unitType)
            ReificationContext.register(declaration, ReificationContext.ContextTypes.DESC, desc)
            // It's important to register function desc before register supertype because of cycle reference
            registerSupertypeSetting(it)
            registerIntsSetting(it)
            registerAnnotationsSetting()
            registerVarRefExpression()
        }
    }

    private fun registerDescVariable(containingDesc: SimpleFunctionDescriptorImpl) {
        descriptor = LocalVariableDescriptor(
            containingDesc,
            Annotations.EMPTY,
            Name.identifier("typeDesc"),
            containingDesc.returnType,
            false,
            false,
            typeDescProperty.toSourceElement()
        )
        ReificationContext.register(typeDescProperty!!, ReificationContext.ContextTypes.VAR, descriptor!!)
    }

    private fun registerSupertypeSetting(containingDesc: SimpleFunctionDescriptorImpl) {
        registerFatherDescriptor(supertypeAssignment!!.right!!, containingDesc)
        val fatherRef = supertypeAssignment!!.left as KtDotQualifiedExpression
        registerVarRefExpression(fatherRef.receiverExpression)
        registerFatherCall(fatherRef, clazz, supertypeAssignment!!.project)
    }

    private fun registerIfCondition() {
        val candidateDesc = clazz.computeExternalType(createHiddenTypeReference(ifExpression!!.project, "Cla"))
            .memberScope.findSingleFunction(Name.identifier("firstReg"))
        val condition = ifExpression!!.condition as KtDotQualifiedExpression
        val receiver = ExpressionReceiver.create(
            condition.receiverExpression,
            clazz.computeExternalType(createHiddenTypeReference(this.project, "Cla")),
            BindingContext.EMPTY
        )
        val call = CallMaker.makeCall(
            receiver,
            condition.operationTokenNode,
            condition.selectorExpression!! as KtCallExpression
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
        resolvedCall.markCallAsCompleted()
        ReificationContext.register(condition.selectorExpression!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
        registerVarRefExpression(condition.receiverExpression)
    }


    private fun registerFatherDescriptor(fatherCreatingCall: KtExpression, containingDesc: SimpleFunctionDescriptorImpl) {
        // father is null
        if (fatherCreatingCall is KtConstantExpression) {
            val params = CompileTimeConstant.Parameters(false, false, false, false, false, false, false)
            val nullConstant = TypedCompileTimeConstant(NullValue(), context.moduleDescriptor, params)
            ReificationContext.register(fatherCreatingCall, ReificationContext.ContextTypes.CONSTANT, nullConstant)
            ReificationContext.register(
                fatherCreatingCall,
                ReificationContext.ContextTypes.TYPE,
                context.builtIns.nullableNothingType
            )
            // father is not null
        } else {
            val father = clazz.getSuperClassOrAny() as LazyClassDescriptor
            if (father.isReified) {
                DescriptorFactoryMethodGenerator(
                    project,
                    father,
                    context
                ).generateDescriptorFactoryMethodIfNeeded(father.companionObjectDescriptor!!)
            }
            registerDescriptorCreatingCall(
                father,
                filterArgumentsForReifiedTypeParams(
                    clazz.defaultType.getImmediateSuperclassNotAny()?.arguments ?: emptyList(),
                    (clazz.defaultType.getImmediateSuperclassNotAny()?.constructor?.declarationDescriptor as? ClassDescriptor)?.declaredReifiedTypeParameters
                        ?: emptyList()
                )
                ,
                containingDesc,
                context,
                fatherCreatingCall as KtDotQualifiedExpression,
                clazz,
                containingDesc.valueParameters[0]
            )
        }
    }

    private fun registerVarRefExpression(expression: KtExpression? = null) {
        val nameReferenceExpression = expression ?: returnExpression
        val call = CallMaker.makeCall(
            nameReferenceExpression!!,
            null,
            null,
            nameReferenceExpression,
            emptyList(),
            Call.CallType.DEFAULT,
            false
        )
        val resolvedCall = ResolvedCallImpl(
            call,
            descriptor!!,
            null,
            null,
            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
            null,
            DelegatingBindingTrace(BindingContext.EMPTY, ""),
            TracingStrategy.EMPTY,
            DataFlowInfoForArgumentsImpl(DataFlowInfo.EMPTY, call)
        )
        resolvedCall.markCallAsCompleted()
        ReificationContext.register(nameReferenceExpression, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }

    private fun registerIntsSetting(containingDesc: SimpleFunctionDescriptorImpl) {
        val interfacesExpression = interafacesAssignment!!.right!! as KtCallExpression
        registerArrayOfResolvedCall(
            clazz,
            interfacesExpression,
            clazz.computeExternalType(createHiddenTypeReference(interfacesExpression.project, "Cla"))
        )
        interfacesExpression.valueArguments.forEachIndexed { index, arg ->
            val callExpression = (arg.getArgumentExpression() as KtDotQualifiedExpression).selectorExpression as KtCallExpression
            DescriptorRegisterCall(
                interfacesExpression.project,
                clazz,
                implementedInterfaces!![index],
                callExpression,
                containingDesc,
                context
            ) {
                registerArrayOfResolvedCall(
                    clazz,
                    callExpression.valueArguments[2].getArgumentExpression() as KtCallExpression,
                    clazz.computeExternalType(createHiddenTypeReference(interfacesExpression.project, "Cla"))
                )
            }.createCallDescriptor()
        }
        val intsRef = interafacesAssignment!!.left as KtDotQualifiedExpression
        registerVarRefExpression(intsRef.receiverExpression)
        registerIntsCall(intsRef, clazz, supertypeAssignment!!.project)
    }

    private fun registerAnnotationsSetting() {
        val annotationsExpression = annotationsAssignment!!.right!! as KtCallExpression
        registerArrayOfResolvedCall(
            clazz,
            annotationsExpression,
            this.context.builtIns.intType
        )
        val annotationList = annotationsExpression.valueArgumentList
        annotationList?.arguments?.forEach { annotation ->
            val annotationExpression = annotation.getArgumentExpression()!!
            if (annotationExpression is KtConstantExpression) {
                registerIntConstant(
                    annotationExpression,
                    context.moduleDescriptor,
                    context.builtIns.intType
                )
            }
        }

        val annotationsRef = annotationsAssignment!!.left as KtDotQualifiedExpression
        registerVarRefExpression(annotationsRef.receiverExpression)
        registerAnnotationsCall(annotationsRef, clazz, annotationsAssignment!!.project)
    }
}
