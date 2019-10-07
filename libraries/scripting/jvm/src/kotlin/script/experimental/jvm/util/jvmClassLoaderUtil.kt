/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jvm.util

import java.io.File
import java.io.InputStream

fun ClassLoader.forAllMatchingFiles(namePattern: String, body: (String, InputStream) -> Unit) {

}

internal val wildcardChars = "*?".toCharArray()
internal val patternCharsToEscape = ".*?+()[]^\${}|".toCharArray().also { assert(wildcardChars.all { wc -> it.contains(wc) }) }

private fun Char.escape(): String = (if (patternCharsToEscape.contains(this)) "\\" else "") + this

internal val pathSeparatorChars = "/".let { if (File.separatorChar == '/') it else it + File.separator }.toCharArray()
internal val pathElementPattern = if (File.separatorChar == '/') "[^/]*" else "[^/${File.separatorChar.escape()}]*"
internal val pathSeparatorPattern = if (File.separatorChar == '/') "/" else "[/${File.separatorChar.escape()}]."
internal val specialPatternChars = patternCharsToEscape + pathSeparatorChars

internal fun forAllMatchingFilesInDirectory(baseDir: File, namePattern: String, body: (String, InputStream) -> Unit) {
    val patternStart = namePattern.indexOfAny(wildcardChars)
    if (patternStart < 0) {
        // assuming a single file
        baseDir.resolve(namePattern).takeIf { it.exists() && it.isFile }?.let { file ->
            body(file.relativeToOrSelf(baseDir).path, file.inputStream())
        }
    } else {
        val patternDirStart = namePattern.lastIndexOfAny(pathSeparatorChars, patternStart)
        val root = if (patternDirStart <= 0) baseDir else baseDir.resolve(namePattern.substring(0, patternDirStart))
        if (root.exists() && root.isDirectory) {
            val re = namePatternToRegex(namePattern.substring(patternDirStart + 1))
            root.walkTopDown().filter { re.matches(it.path) }.forEach { file ->
                body(file.relativeToOrSelf(baseDir).path, file.inputStream())
            }
        }
    }
}

internal fun namePatternToRegex(pattern: String): Regex = Regex(
    buildString {
        var current = 0
        loop@ while (current < pattern.length) {
            val nextIndex = pattern.indexOfAny(specialPatternChars, current)
            val next = if (nextIndex < 0) pattern.length else nextIndex
            append(pattern.substring(current, next))
            current = next + 1
            when {
                next >= pattern.length -> break@loop

                pathSeparatorChars.contains(pattern[next]) -> append(pathSeparatorPattern)

                pattern[next] == '?' -> append('.')

                pattern[next] == '*' && next + 1 < pattern.length && pattern[next + 1] == '*' -> {
                    append(".*")
                    current++
                }

                pattern[next] == '*' -> append(pathElementPattern)

                else -> {
                    append('\\')
                    append(pattern[next])
                }
            }
        }
    }
)