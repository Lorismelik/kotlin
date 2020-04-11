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
    val type: KClass<*>? = null
) {
    private val hashValue: Int
    var father: Cla? = null

    init {
        hashValue = processHash()
    }

    private fun processHash() = type.hashCode() * p.contentHashCode()

    override fun hashCode(): Int {
        return hashValue
    }

    fun safeCast(o: Any?): Any? {
        return if (this.isInstance(o)) o else null
    }

    fun cast(o: Any?): Any {
        return if (this.isInstance(o)) o!! else throw ClassCastException()
    }

    fun isInstance(o: Any?): Boolean {
        var oDesc: Cla? = null
        if (o is Parametric) oDesc = o.getD()
        if (o is Cla) oDesc = o
        if (oDesc == Man.starProjection) return true
        if (oDesc != null) {
            while (oDesc!!.type != this.type && oDesc.father != null) {
                oDesc = oDesc.father!!
            }
            if (oDesc.type == this.type) {
                this.p.forEachIndexed { index, thisParam ->
                    val thatParam = oDesc.p[index]
                    val lower = thatParam.bounds?.first
                    val upper = thatParam.bounds?.second
                    if (!lower!!.isInstance(thisParam.bounds!!.first!!)) {
                        return false
                    }
                    if (!thisParam.bounds!!.second!!.isInstance(upper)) {
                        return false
                    }
                }
                return true
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


    open class Cla(
        p: Array<Cla>,
        pureInstanceCheck: (Any?) -> Boolean,
        type: KClass<*>?,
        val annotations: Array<Int>,
        id: Int = -1
    ) : _D(p, id, pureInstanceCheck, type) {
        var bounds: Pair<Cla?, Cla?>? = null

        var freshTypeVariable = false

        init {
            p.forEachIndexed() { index, cla ->
                cla.bounds = when (annotations[index]) {
                    Variance.INVARIANT.ordinal -> Pair(cla, cla)
                    Variance.OUT.ordinal -> Pair(Man.nothingDesc, cla)
                    Variance.IN.ordinal -> Pair(cla, Man.anyDesc)
                    Variance.BIVARIANT.ordinal -> Pair(Man.nothingDesc, Man.anyDesc)
                    else -> throw Exception("Illegal annotation for reified parameter")
                }
                if (annotations[index] != Variance.INVARIANT.ordinal) cla.freshTypeVariable = true
            }
        }

        private var new = true

        fun firstReg(): Boolean {
            return if (!new) {
                false
            } else {
                new = false
                true
            }
        }
    }

    object Man {
        private val descTable: HashMap<Int, Cla> = HashMap(101, 0.75f)
        var countId = 1
        val anyDesc = _D.Cla(arrayOf(), { true }, Any::class, arrayOf())
        val nothingDesc = _D.Cla(arrayOf(), { it is Nothing? }, Nothing::class, arrayOf())
        val starProjection = _D.Cla(arrayOf(), { true }, null, arrayOf()).apply { bounds = Pair(Man.nothingDesc, Man.anyDesc) }

        init {
            anyDesc.id = countId
            descTable[anyDesc.hashCode()] = anyDesc;
            nothingDesc.id = ++countId
            descTable[nothingDesc.hashCode()] = nothingDesc;
        }

        fun register(
            pureCheck: (Any?) -> Boolean,
            type: KClass<*>,
            p: Array<Cla> = arrayOf(),
            a: Array<Int> = arrayOf(),
            father: Cla? = null
        ): Cla {
            println(type)
            val desc = Cla(p, pureCheck, type, a)
            val o = descTable[desc.hashCode()]
            if (o == null) {
                desc.id = countId++
                descTable[desc.hashCode()] = desc;
                if (father != null) {
                    desc.father = father
                }
                return desc;
            }
            return o
        }
    }

    interface Parametric {
        fun getD(): Cla
    }

    enum class Variance() {
        INVARIANT,
        OUT,
        IN,
        BIVARIANT
    }
}