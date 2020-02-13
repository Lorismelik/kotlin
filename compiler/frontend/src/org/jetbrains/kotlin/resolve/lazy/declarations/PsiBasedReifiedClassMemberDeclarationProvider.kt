/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.lazy.declarations

import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.reification.ReificationResolver
import org.jetbrains.kotlin.resolve.lazy.data.KtClassLikeInfo
import org.jetbrains.kotlin.storage.StorageManager

class PsiBasedReifiedClassMemberDeclarationProvider(
    storageManager: StorageManager,
    override val ownerInfo: KtClassLikeInfo
) : AbstractPsiBasedDeclarationProvider(storageManager), ClassMemberDeclarationProvider {

    override val primaryConstructorParameters: List<KtParameter> get() = ownerInfo.primaryConstructorParameters + extraParameter
    private val extraParameter = ReificationResolver.resolveConstructorParameter(ownerInfo.correspondingClassOrObject!!)
    override fun doCreateIndex(index: AbstractPsiBasedDeclarationProvider.Index) {
        for (declaration in ownerInfo.declarations) {
            index.putToIndex(declaration)
        }

        for (parameter in ownerInfo.primaryConstructorParameters) {
            if (parameter.hasValOrVar()) {
                index.putToIndex(parameter)
            }
        }
        index.putToIndex(extraParameter)
    }

    override fun toString() = "Declarations for $ownerInfo"
}