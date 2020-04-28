/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf

abstract class _D(
    val p: Array<Cla>?,
    var id: Int,
    val type: KClass<*>? = null
) {
    private val hashValue: Int
    var father: Cla? = null
    var annotations: Array<Int> = arrayOf()

    init {
        hashValue = processHash()
    }

    private fun processHash() = type.hashCode() * 31 + (p?.contentHashCode() ?: 0) * 31

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
        // This type is Any or That is Nothing that isInstance is always true
        if (this == Man.anyDesc || o == Man.nothingDesc) return true
        var oDesc: Cla? = null
        // if That type is Cla we came from isInstanceForFreshTypeVars method
        if (o is Cla) {
            if (o.p == null && o.father == null) {
                // if this type is reified generic (father is not null or have parameters) isInstance is always false
                if (this.p != null || this.father != null) {
                    println("That type is not parametric but this type is parametric")
                    return false
                }
                println("Check for SuperClass")
                return this.type == o.type || this.type!!.isSuperclassOf(o.type!!)
            } else {
                oDesc = o
            }
        }
        // if that type is Parametric we need to walk through inheritance chain and find for type that equals This type.
        // After that we need to compare their parameters based on their variance
        if (o is Parametric) oDesc = o.getD()
        if (oDesc != null) {
            while (oDesc!!.type != this.type && oDesc.father != null) {
                oDesc = oDesc.father!!
            }
            if (oDesc.type == this.type) {
                this.p?.forEachIndexed { index, thisParam ->
                    val checkBounds = annotations[index] != Variance.INVARIANT.ordinal
                    if (!thisParam.isInstanceForFreshTypeVars(
                            oDesc.p!![index],
                            arrayOf(this.annotations[index], oDesc.annotations[index]),
                            checkBounds
                        )
                    ) {
                        println("Params not equal ${oDesc.p!![index].type} is ${thisParam.type} for ${oDesc.type} is ${this.type}")
                        return false
                    }
                }
                return true
            }
            println("Types not equal ${oDesc.type} is ${this.type}")
            return false
        } else {
            // If that type is not Parametric then there is only one right case, that This type is not reified generic too
            println("Pure check $o is ${this.type}")
            if (this.p != null || this.father != null) return false
            return type!!.isInstance(o)
        }
    }

    protected fun isInstanceForFreshTypeVars(o: Cla, annotations: Array<Int>, checkBounds: Boolean = true): Boolean {
        // if this type is star projection then isInstanceForFreshTypeVars is always true
        if (this == Man.starProjection) return true
        // checkBounds is a marker for invariant check. If it is false we compare types for strict equality
        if (!checkBounds) {
            if (this.type != o.type || annotations[1] != annotations[0]) {
                println("Types not equal or annotations ${o.type} is ${this.type} with annotations ${annotations[1]} and ${annotations[0]}")
                return false
            }
            this.p?.forEachIndexed { index, thisParam ->
                if (!thisParam.isInstanceForFreshTypeVars(
                        o.p!![index],
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
            // if checkBounds is true then we split projection for 2 bounds and compare them
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
            else -> throw Exception("Illegal annotation for reified parameter")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is _D) return false
        if (p != null && other.p != null) {
            if (!p.contentEquals(other.p)) return false
        } else if (!(p == null && other.p == null)) {
            return false
        }
        if (type != other.type) return false

        return true
    }


    open class Cla(
        p: Array<Cla>?,
        type: KClass<*>?,
        id: Int = -1
    ) : _D(p, id, type) {
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
        val anyDesc = _D.Cla(arrayOf(), Any::class)
        val nothingDesc = _D.Cla(arrayOf(), Nothing::class)
        val starProjection = _D.Cla(arrayOf(), null)

        init {
            anyDesc.id = countId
            descTable[anyDesc.hashCode()] = anyDesc;
            nothingDesc.id = ++countId
            descTable[nothingDesc.hashCode()] = nothingDesc;
        }

        fun register(
            type: KClass<*>,
            p: Array<Cla>? = null
        ): Cla {
            val desc = Cla(p, type)
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

    enum class Variance() {
        INVARIANT,
        IN,
        OUT
    }
}