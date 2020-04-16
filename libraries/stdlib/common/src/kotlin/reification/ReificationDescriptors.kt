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
    val annotations: Array<Int>,
    val type: KClass<*>? = null,
    val isInterface: Boolean = false
) {
    private val hashValue: Int
    var father: Cla? = null
    var ints: Array<Cla> = arrayOf()

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
        if (this == Man.anyDesc || o == Man.nothingDesc) return true
        var oDesc: Cla? = null
        if (o is Parametric) oDesc = o.getD()
        if (o is Cla) oDesc = o
        if (oDesc != null) {
            if (oDesc.isInterface && !this.isInterface) {
                return false
            }
            if (this.isInterface) {
                return oDesc.ints.any { it == this }
            }
            while (oDesc!!.type != this.type && oDesc.father != null) {
                oDesc = oDesc.father!!
            }
            if (oDesc.type == this.type) {
                this.p.forEachIndexed { index, thisParam ->
                    val checkBounds = annotations[index] != Variance.INVARIANT.ordinal
                    if (!thisParam.isInstanceForFreshTypeVars(
                            oDesc.p[index],
                            arrayOf(this.annotations[index], oDesc.annotations[index]),
                            checkBounds
                        )
                    ) {
                        println("Params not equal ${oDesc.p[index].type} is ${thisParam.type} for ${oDesc.type} is ${this.type}")
                        return false
                    }
                }
                return true
            }
            println("Types not equal ${oDesc.type} is ${this.type}")
            return false
        } else {
            println("Pure check $o is ${this.type}")
            return pureInstanceCheck(o)
        }
    }

    protected fun isInstanceForFreshTypeVars(o: Cla, annotations: Array<Int>, checkBounds: Boolean = true): Boolean {
        if (this == Man.starProjection) return true
        if (!checkBounds) {
            if (this.type != o.type || annotations[1] != annotations[0]) {
                println("Types not equal or annotations ${o.type} is ${this.type} with annotations ${annotations[1]} and ${annotations[0]}")
                return false
            }
            this.p.forEachIndexed { index, thisParam ->
                if (!thisParam.isInstanceForFreshTypeVars(
                        o.p[index],
                        arrayOf(this.annotations[index], o.annotations[index]),
                        checkBounds
                    )
                ) {
                    println("Params not equal ${o.p[index].type} is ${thisParam.type} for ${o.type} is ${this.type}")
                    return false
                }
            }
            return true
        } else {
            val thisBound = createBounds(annotations[0], this as Cla)
            val thatBound = createBounds(annotations[1], o)
            if (!thatBound.first.isInstance(thisBound.first) || !thisBound.second.isInstance(thatBound.second)) {
                println("Bounds not equal for ${o.type} is ${this.type}")
                return false
            }
            return true
        }
    }

    private fun createBounds(variance: Int, cla: Cla): Pair<Cla, Cla> {
        return when (variance) {
            Variance.INVARIANT.ordinal -> Pair(cla, cla)
            Variance.OUT.ordinal -> Pair(Man.nothingDesc, cla)
            Variance.IN.ordinal -> Pair(cla, Man.anyDesc)
            Variance.BIVARIANT.ordinal -> Pair(Man.nothingDesc, Man.anyDesc)
            else -> throw Exception("Illegal annotation for reified parameter")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is _D) return false

        //if (!p.contentEquals(other.p)) return false
        if (type != other.type) return false

        return true
    }


    open class Cla(
        p: Array<Cla>,
        pureInstanceCheck: (Any?) -> Boolean,
        type: KClass<*>?,
        annotations: Array<Int>,
        isInterface: Boolean = false,
        id: Int = -1
    ) : _D(p, id, pureInstanceCheck, annotations, type, isInterface) {
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
        val starProjection = _D.Cla(arrayOf(), { true }, null, arrayOf())

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
            father: Cla? = null,
            ints: Array<Cla> = arrayOf(),
            isInterface: Boolean = false
        ): Cla {
            val desc = Cla(p, pureCheck, type, a, isInterface)
            val o = descTable[desc.hashCode()]
            if (o == null) {
                desc.id = countId++
                descTable[desc.hashCode()] = desc;
                if (father != null) {
                    desc.father = father
                }
                if (ints.isNotEmpty()) {
                    desc.ints = ints
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