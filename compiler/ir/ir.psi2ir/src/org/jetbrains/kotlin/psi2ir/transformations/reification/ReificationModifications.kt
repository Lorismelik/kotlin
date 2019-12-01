package org.jetbrains.kotlin.psi2ir.transformations.reification
import com.intellij.lang.ASTFactory
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.types.KotlinType

class lol(){}

fun createHiddenTypeReference(typeName: String): KtTypeReference? {
    val firstPackage = ASTFactory.leaf(KtToken("IDENTIFIER"), "kotlin")
    val secondPackage = ASTFactory.leaf(KtToken("IDENTIFIER"), "reification")
    val type = ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
        rawAddChildren(ASTFactory.leaf(KtToken("IDENTIFIER"), typeName))
    }
    val lol = lol()
    lol.javaClass
    val prefix = ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
        rawAddChildren(
            ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
                rawAddChildren(
                    ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                        rawAddChildren(firstPackage)
                    }
                )
            }
        )
        rawAddChildren(ASTFactory.leaf(KtSingleValueToken("DOT", "."), "."))
        rawAddChildren(
            ASTFactory.composite(KtNodeTypes.REFERENCE_EXPRESSION).apply {
                rawAddChildren(secondPackage)
            }
        )
    }

    return KtTypeReference(ASTFactory.composite(KtNodeTypes.TYPE_REFERENCE).apply {
        rawAddChildren(
            ASTFactory.composite(KtNodeTypes.USER_TYPE).apply {
                rawAddChildren(prefix)
                rawAddChildren(ASTFactory.leaf(KtSingleValueToken("DOT", "."), "."))
                rawAddChildren(type)
            }
        )
    })
}

fun LazyClassDescriptor.resolveParametricSupertype(): KotlinType {
    val parametricRef = createHiddenTypeReference("Parametric1")
    return this.computeExternalSupertype(parametricRef)
}
