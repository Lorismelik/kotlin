/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirClassSubstitutionScope
import org.jetbrains.kotlin.fir.scopes.impl.FirClassUseSiteScope
import org.jetbrains.kotlin.fir.scopes.impl.FirSuperTypeScope
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassifierSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import java.util.*

fun lookupSuperTypes(
    klass: FirRegularClass,
    lookupInterfaces: Boolean,
    deep: Boolean,
    useSiteSession: FirSession
): List<ConeClassLikeType> {
    return mutableListOf<ConeClassLikeType>().also {
        klass.symbol.collectSuperTypes(it, mutableSetOf(), deep, lookupInterfaces, useSiteSession)
    }
}

class ScopeSession {
    private val scopes = hashMapOf<FirClassifierSymbol<*>, EnumMap<ScopeSessionKey, FirScope>>()
    fun <T : FirScope> getOrBuild(symbol: FirClassifierSymbol<*>, key: ScopeSessionKey, build: () -> T): T {
        return scopes.getOrPut(symbol) {
            EnumMap(ScopeSessionKey::class.java)
        }.getOrPut(key) {
            build()
        } as T
    }
}

enum class ScopeSessionKey {
    USE_SITE,
    JAVA_USE_SITE,
    JAVA_ENHANCEMENT
}

fun FirRegularClass.buildUseSiteScope(useSiteSession: FirSession, builder: ScopeSession): FirScope? {
    val symbolProvider = useSiteSession.firSymbolProvider
    return symbolProvider.getClassUseSiteMemberScope(this.classId, useSiteSession, builder)
}

fun FirTypeAlias.buildUseSiteScope(useSiteSession: FirSession, builder: ScopeSession): FirScope? {
    val type = expandedTypeRef.coneTypeUnsafe<ConeClassLikeType>()
    return type.scope(useSiteSession, builder)?.let {
        type.wrapSubstitutionScopeIfNeed(useSiteSession, it, this, builder)
    }
}

fun FirRegularClass.buildDefaultUseSiteScope(useSiteSession: FirSession, builder: ScopeSession): FirScope {
    return builder.getOrBuild(symbol, ScopeSessionKey.USE_SITE) {

        val declaredScope = declaredMemberScope(this)
        val scopes = lookupSuperTypes(this, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
            .mapNotNull { useSiteSuperType ->
                if (useSiteSuperType is ConeClassErrorType) return@mapNotNull null
                val symbol = useSiteSuperType.lookupTag.toSymbol(useSiteSession)
                if (symbol is FirClassSymbol) {
                    val useSiteScope = symbol.fir.buildUseSiteScope(useSiteSession, builder)!!
                    useSiteSuperType.wrapSubstitutionScopeIfNeed(useSiteSession, useSiteScope, symbol.fir, builder)
                } else {
                    null
                }
            }
        FirClassUseSiteScope(useSiteSession, FirSuperTypeScope(useSiteSession, scopes), declaredScope)
    }
}

fun ConeClassLikeType.wrapSubstitutionScopeIfNeed(
    session: FirSession,
    useSiteScope: FirScope,
    declaration: FirClassLikeDeclaration<*>,
    builder: ScopeSession
): FirScope {
    if (this.typeArguments.isEmpty()) return useSiteScope
    return run {
        @Suppress("UNCHECKED_CAST")
        val substitution = declaration.typeParameters.zip(this.typeArguments) { typeParameter, typeArgument ->
            typeParameter.symbol to (typeArgument as? ConeTypedProjection)?.type
        }.filter { (_, type) -> type != null }.toMap() as Map<FirTypeParameterSymbol, ConeKotlinType>

        FirClassSubstitutionScope(session, useSiteScope, builder, substitution)
    }
}

private tailrec fun ConeClassLikeType.computePartialExpansion(useSiteSession: FirSession): ConeClassLikeType? {
    return when (this) {
        is ConeAbbreviatedType -> directExpansionType(useSiteSession)?.computePartialExpansion(useSiteSession)
        else -> this
    }
}

private fun FirClassifierSymbol<*>.collectSuperTypes(
    list: MutableList<ConeClassLikeType>,
    visitedSymbols: MutableSet<FirClassifierSymbol<*>>,
    deep: Boolean,
    lookupInterfaces: Boolean,
    useSiteSession: FirSession
) {
    if (!visitedSymbols.add(this)) return
    when (this) {
        is FirClassSymbol -> {
            val superClassTypes =
                fir.superConeTypes.mapNotNull {
                    it.computePartialExpansion(useSiteSession)
                        .takeIf { type -> lookupInterfaces || type.isClassBasedType(useSiteSession) }
                }
            list += superClassTypes
            if (deep)
                superClassTypes.forEach {
                    if (it !is ConeClassErrorType) {
                        it.lookupTag.toSymbol(useSiteSession)?.collectSuperTypes(
                            list,
                            visitedSymbols,
                            deep,
                            lookupInterfaces,
                            useSiteSession
                        )
                    }
                }
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.computePartialExpansion(useSiteSession) ?: return
            expansion.lookupTag.toSymbol(useSiteSession)?.collectSuperTypes(list, visitedSymbols, deep, lookupInterfaces, useSiteSession)
        }
        else -> error("?!id:1")
    }
}

private fun ConeClassLikeType?.isClassBasedType(
    useSiteSession: FirSession
) = this !is ConeClassErrorType &&
        (this?.lookupTag?.toSymbol(useSiteSession) as? FirClassSymbol)?.fir?.classKind == ClassKind.CLASS
