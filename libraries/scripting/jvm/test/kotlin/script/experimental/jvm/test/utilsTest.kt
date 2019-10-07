/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jvm.test

import junit.framework.TestCase
import org.junit.Test
import java.io.File
import kotlin.script.experimental.jvm.util.forAllMatchingFilesInDirectory
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

    @Test
    fun testSelectFilesInDir() {

        val rootDir = File(".")

        fun assertProjectFilesBy(pattern: String, vararg paths: String) {
            val res = ArrayList<Pair<String, String>>()

            forAllMatchingFilesInDirectory(rootDir, pattern) { path, stream ->
                res.add(path to stream.reader().readText())
            }
            assertEquals(paths.asIterable().toSet(), res.mapTo(HashSet()) { it.first })

            res.forEach { (path, bytes) ->
                val data = File(path).readText()
                assertEquals("Mismatching data for $path", data, bytes)
            }
        }

        assertProjectFilesBy("*.kt") // none
        assertProjectFilesBy("**/sss/*.kt") // none
        assertProjectFilesBy(
            "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt",
            "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt"
        )
        assertProjectFilesBy(
            "src/kotlin/script/experimental/jvm/util/jvm?lassLoaderUtil.kt",
            "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt"
        )
        assertProjectFilesBy(
            "src/kotlin/script/experimental/jvm/util/jvm*LoaderUtil.kt",
            "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt"
        )
        assertProjectFilesBy("**/jvmClassLoaderUtil.kt", "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt")
        assertProjectFilesBy("**/script/**/jvmClassLoaderUtil.kt", "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt")
        assertProjectFilesBy("src/**/jvmClassLoaderUtil.kt", "src/kotlin/script/experimental/jvm/util/jvmClassLoaderUtil.kt")
        assertProjectFilesBy("test/**/?????Test.*", "test/kotlin/script/experimental/jvm/test/utilsTest.kt")

        val allSrcKtFiles = HashSet<String>()
        forAllMatchingFilesInDirectory(rootDir, "src/**/*.kt") { path, _ ->
            allSrcKtFiles.add(path)
        }
        val allExpectedSrcKtFiles =
            rootDir.walkTopDown().filter {
                it.relativeToOrSelf(rootDir).path.startsWith("src") && it.extension == "kt"
            }.mapTo(HashSet()) {
                it.relativeToOrSelf(rootDir).path
            }
        assertEquals(allExpectedSrcKtFiles, allSrcKtFiles)
    }

    private fun assertPattern(expected: String, pattern: String) {
        assertEquals(expected, namePatternToRegex(pattern).pattern)
    }

}

