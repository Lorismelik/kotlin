/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.scripting.experimental.jvm.test

import junit.framework.TestCase
import org.junit.Test
import kotlin.script.experimental.jvm.util.namePatternToRegex
import kotlin.script.experimental.jvm.util.pathElementPattern
import kotlin.script.experimental.jvm.util.pathSeparatorPattern

class UtilsTest : TestCase() {

    @Test
    fun testPatternConversionWildcards() {
        assertPattern("a${pathSeparatorPattern}b\\.$pathElementPattern", "a/b.*")
        assertPattern("a$pathSeparatorPattern$pathElementPattern\\.txt", "a/*.txt")
        assertPattern("a$pathSeparatorPattern.*/b", "a/**/b")
        assertPattern("a${pathSeparatorPattern}b.\\.txt", "a/b?.txt")
        assertPattern("$pathElementPattern/b\\.txt", "*/b.txt")
        assertPattern(".*${pathSeparatorPattern}b\\.txt", "**/b.txt")
    }

    @Test
    fun testPatternConversionEscaping() {
        assertPattern("aa\\+\\(\\)\\[\\]\\^\\\$\\{\\}\\|", "aa+()[]^\${}|")
        assertPattern("\\+\\(\\)\\[\\]\\^\\\$\\{\\}\\|bb", "+()[]^\${}|bb")
    }

    private fun assertPattern(expected: String, pattern: String) {
        assertEquals(expected, namePatternToRegex(pattern).pattern)
    }
}

