/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package kotlin.reification

import kotlin.reflect.KClass

abstract class _D(
    val p: Array<Cla>,
    var id: Int,
    val pureInstanceCheck: (Any?) -> Boolean,
    val father: Cla?,
    val type: KClass<*>
) {
    private val hashValue: Int

    init {
        hashValue = processHash()
    }

    private fun processHash() = type.hashCode() * p.contentHashCode()

    override fun hashCode(): Int {
        return hashValue
    }

    class Cla(
        p: Array<Cla>,
        pureInstanceCheck: (Any?) -> Boolean,
        father: Cla?,
        type: KClass<*>,
        id: Int = -1
    ) : _D(p, id, pureInstanceCheck, father, type) {
    }

    fun isInstance(o: Any?): Boolean {
        if (o is Parametric) {
            throw IllegalArgumentException("Not now")
        } else {
            return pureInstanceCheck(o)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is _D) return false

        if (!p.contentEquals(other.p)) return false
        if (type != other.type) return false

        return true
    }

    object Man {
        val descTable: HashMap<Int, Cla> = HashMap(101, 0.75f)
        var countId = 1
        fun register(pureCheck: (Any?) -> Boolean, father: Cla?, type: KClass<*>, p: Array<Cla> = arrayOf()): Cla {
            val desc = Cla(p, pureCheck, father, type)
            val o = descTable[desc.hashCode()]
            if (o == null) {
                println("Single Reg!!!")
                desc.id = countId++
                descTable[desc.hashCode()] = desc;
                return desc;
            } else {
                println("Double Reg!!!")
            }
            return o
        }
    }

    interface Parametric {
        fun getD(): Cla
    }
}