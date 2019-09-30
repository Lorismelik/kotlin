/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import com.intellij.util.SmartList
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.*
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartProviderBase
import org.jetbrains.kotlin.cli.jvm.compiler.tryLoadModuleMapping
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.metadata.jvm.deserialization.ModuleMapping
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import java.io.*
import java.util.jar.JarInputStream

class PackagePartFromClassLoaderProvider(
    languageVersionSettings: LanguageVersionSettings
) : JvmPackagePartProviderBase<String>() {
    private val deserializationConfiguration = CompilerDeserializationConfiguration(languageVersionSettings)

    override val loadedModules: MutableList<ModuleMappingInfo<String>> = SmartList()

    fun addRoots(roots: List<File>, messageCollector: MessageCollector) {
        for (root in roots) {
            when {
                root.isDirectory -> loadModulesFromClassesDir(root, messageCollector)
                root.isFile && root.extension.equals("jar", ignoreCase = true) -> loadModulesFromJar(root, messageCollector)
                else -> messageCollector.report(WARNING, "Unknown module type $root")
            }
        }
    }

    private fun loadModulesFromClassesDir(root: File, messageCollector: MessageCollector) {
        val metaInf = File(root, "META-INF")
        if (metaInf.exists() && metaInf.isDirectory) {
            metaInf.listFiles { _, name ->
                name?.endsWith(ModuleMapping.MAPPING_FILE_EXT) == true
            }?.forEach { moduleFile ->
                tryLoadModuleMapping(
                    { moduleFile.readBytes() }, moduleFile.path, moduleFile.path, deserializationConfiguration, messageCollector
                )?.let {
                    loadedModules.add(ModuleMappingInfo(moduleFile.path, it, moduleFile.nameWithoutExtension))
                }
            }
        }
    }

    private fun loadModulesFromJar(jar: File, messageCollector: MessageCollector) {
        JarInputStream(FileInputStream(jar)).use { jarInputStream ->
            while (true) {
                val entry = jarInputStream.nextJarEntry ?: break
                try {
                    if (!entry.isDirectory && entry.name.startsWith("META-INF/") && entry.name.endsWith(ModuleMapping.MAPPING_FILE_EXT)) {
                        val entryKey = "${jar}!${entry.name}"
                        tryLoadModuleMapping(
                            { jarInputStream.readBytes() }, entryKey, entry.name, deserializationConfiguration, messageCollector
                        )?.let {
                            val moduleName = entry.name.removePrefix("META-INF/").removeSuffix(".${ModuleMapping.MAPPING_FILE_EXT}")
                            loadedModules.add(ModuleMappingInfo(entryKey, it, moduleName))
                        }
                    }
                } finally {
                    jarInputStream.closeEntry()
                }
            }
        }
    }
}

