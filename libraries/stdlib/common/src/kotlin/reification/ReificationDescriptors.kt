/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reification

import kotlin.reflect.KClass

abstract class _D(
    val p: Array<_D>,
    val f: Array<_D>,
    val encl: _D?,
    val id: Int,
    val instanceCheck: (Any) -> Boolean
) {
    class Cla(
        val h: Array<Cla>,
        val cl: KClass<*>,
        val depth: Int,
        instanceCheck: (Any) -> Boolean,
        p: Array<_D>,
        f: Array<_D>,
        encl: _D?,
        id: Int
    ) : _D(p, f, encl, id, instanceCheck) {}
}