/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package kotlin.reification

abstract class _D(
    val p: Array<_D>,
    var id: Int,
    val pureInstanceCheck: (Any) -> Boolean
) {
    private val hashValue: Int

    init {
        hashValue = processHash()
    }

    private fun processHash() = pureInstanceCheck.hashCode() * p.hashCode()

    override fun hashCode(): Int {
        return hashValue
    }

    class Cla(
        p: Array<_D>,
        pureInstanceCheck: (Any) -> Boolean,
        id: Int = -1
    ) : _D(p, id, pureInstanceCheck) {
    }

    fun isInstance(o: Any): Boolean {
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
        if (pureInstanceCheck != other.pureInstanceCheck) return false

        return true
    }

    object Man {
        val descTable: HashMap<Int, _D.Cla> = HashMap(101,0.75f)
        var countId = 1
        fun register(pureCheck: (Any) -> Boolean, p : Array<_D> = arrayOf()): _D.Cla {
            val desc = Cla(p, pureCheck)
            val o = descTable[desc.hashCode()]
            if (o==null) {
                desc.id = countId++
                descTable[desc.hashCode()] = desc;
                return desc;
            }
            return o
        }
    }

    interface Parametric {
        fun getD(): _D.Cla
    }
}