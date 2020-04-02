/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.transformations.reification.synthetic

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.transformations.reification.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.ValueArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.model.DataFlowInfoForArgumentsImpl
import org.jetbrains.kotlin.resolve.calls.model.NamedArgumentReference
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
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.resolve.reification.ReificationContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.asSimpleType
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import java.lang.StringBuilder


// Factory Descriptor Creating
// Something like:
/*fun createTD(p: Array<_D>): _D {
    return kotlin.reification._D.Man.register({it is C<*>}, p )
}*/
class DescriptorFactoryMethodGenerator(val project: Project, val clazz: LazyClassDescriptor, val context: GeneratorContext) {

    var registerCall: KtCallExpression? = null
    var argumentReference: KtNameReferenceExpression? = null
    var typeDescProperty: KtProperty? = null
    var descriptor: LocalVariableDescriptor? = null
    var supertypeAssignment: KtBinaryExpression? = null
    var returnExpression: KtNameReferenceExpression? = null

    private fun createByFactory(): KtNamedFunction {
        val typeRef = createTextTypeReferenceWithStarProjection(this.clazz.defaultType)
        val fatherDescriptor = fatherDescriptorRegisteringCode()
        val newText = """fun createTD(p: Array<kotlin.reification._D.Cla>): kotlin.reification._D.Cla { 
                |val desc = kotlin.reification._D.Man.register({it is $typeRef}, ${this.clazz.defaultType.constructor} :: class, p) 
                |desc.father = $fatherDescriptor
                |return desc
                |}""".trimMargin()
        val oldText = """fun createTD(p: Array<kotlin.reification._D.Cla>): kotlin.reification._D.Cla { 
                |return kotlin.reification._D.Man.register({it is $typeRef}, ${this.clazz.defaultType.constructor} :: class, p)
                |}""".trimMargin()
        return KtPsiFactory(project, false).createFunction(
            newText
        ).apply {
            val statements = this.bodyBlockExpression!!.statements
            typeDescProperty = statements[0] as KtProperty
            supertypeAssignment = statements[1] as KtBinaryExpression
            returnExpression = PsiTreeUtil.findChildOfType(statements[2], KtNameReferenceExpression::class.java)
            registerCall = PsiTreeUtil.findChildOfType(statements[0], KtCallExpression::class.java)
            val valueArgList = PsiTreeUtil.findChildOfType(statements[0], KtValueArgumentList::class.java)
            val pureCheckExpression = PsiTreeUtil.findChildOfType(valueArgList!!.arguments[0], KtIsExpression::class.java)!!
            ReificationContext.register(pureCheckExpression, ReificationContext.ContextTypes.REIFICATION_CONTEXT, true)
            argumentReference = PsiTreeUtil.findChildOfType(valueArgList.arguments.last(), KtNameReferenceExpression::class.java)
        }
    }

    private fun fatherDescriptorRegisteringCode(): String {
        val supertype = clazz.getSuperClassNotAny()
        return if (supertype == null || !supertype.isReified) "null"
        else {
            val childReifiedTypeParams = clazz.declaredReifiedTypeParameters
            createCodeForDescriptorFactoryMethodCall({
                                                         val reifiedTypeInstances =
                                                             clazz.defaultType.constructor.supertypes.first()
                                                                 .arguments.filterIndexed { index, _ ->
                                                                 supertype.declaredTypeParameters[index].isReified
                                                             }
                                                         reifiedTypeInstances.map {
                                                             with(StringBuilder()) {
                                                                 append(
                                                                     createTypeParameterDescriptorSource(
                                                                         it.type,
                                                                         childReifiedTypeParams,
                                                                         true
                                                                     )
                                                                 )
                                                             }
                                                         }.joinToString()
                                                     }, supertype)

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
        val parameter = ValueParameterDescriptorImpl(
            desc, null, 0, Annotations.EMPTY, Name.identifier("p"),
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
            listOf(parameter),
            returnType,
            Modality.FINAL,
            Visibilities.PUBLIC
        ).also {
            registerDescVariable(it)
            DescriptorRegisterCall(project, clazz, registerCall!!, it, context) {
                registerResolvedCallForParameter(
                    argumentReference!!,
                    it.valueParameters.first()
                )
            }.createCallDescriptor()
            ReificationContext.register(declaration, ReificationContext.ContextTypes.DESC, desc)
            // It's important to register function desc before register supertype because of cycle reference
            registerSupertypeSetting(it)
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
                clazz.typeConstructor.supertypes.firstOrNull()?.arguments ?: emptyList(),
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
        ReificationContext.register(nameReferenceExpression!!, ReificationContext.ContextTypes.RESOLVED_CALL, resolvedCall)
    }
}
