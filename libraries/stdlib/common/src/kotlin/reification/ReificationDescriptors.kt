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
    val type: KClass<*>
) {
    private val hashValue: Int
    var father: Cla? = null

    init {
        hashValue = processHash()
    }

    fun setSupertype(supertype: _D.Cla) {
        father = supertype
    }

    private fun processHash() = type.hashCode() * p.contentHashCode()

    override fun hashCode(): Int {
        return hashValue
    }

    class Cla(
        p: Array<Cla>,
        pureInstanceCheck: (Any?) -> Boolean,
        type: KClass<*>,
        id: Int = -1
    ) : _D(p, id, pureInstanceCheck, type) {
    }

    fun safeCast(o: Any?): Any? {
        return if (this.isInstance(o)) o else null
    }

    fun cast(o: Any?): Any {
        return if (this.isInstance(o)) o!! else throw ClassCastException()
    }

    fun isInstance(o: Any?): Boolean {
        if (this.father == null && p.isEmpty()) {
            return pureInstanceCheck(o)
        }
        if (o is Parametric) {
            var oDesc = o.getD()
            if (oDesc.id == this.id) return true
            while (oDesc.father != null) {
                if (oDesc.father!!.id == this.id) {
                    return true
                } else {
                    oDesc = oDesc.father!!
                }
            }
            return false
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
        fun register(pureCheck: (Any?) -> Boolean, type: KClass<*>, p: Array<Cla> = arrayOf()): Cla {
            val desc = Cla(p, pureCheck, type)
            val o = descTable[desc.hashCode()]
            if (o == null) {
                desc.id = countId++
                descTable[desc.hashCode()] = desc;
                return desc;
            }
            return o
        }
    }

    interface Parametric {
        fun getD(): Cla
    }
}