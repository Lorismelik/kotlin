/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import com.intellij.util.SmartList
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.*
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.load.kotlin.loadModuleMapping
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.ModuleMapping
import org.jetbrains.kotlin.metadata.jvm.deserialization.PackageParts
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.serialization.deserialization.MetadataPartProvider
import java.io.*
import java.util.jar.JarInputStream

class PackagePartFromClassLoaderProvider(
    languageVersionSettings: LanguageVersionSettings
) : PackagePartProvider, MetadataPartProvider {
    private data class ModuleMappingInfo(val mapping: ModuleMapping, val name: String)

    private val deserializationConfiguration = CompilerDeserializationConfiguration(languageVersionSettings)

    private val loadedModules: MutableList<ModuleMappingInfo> = SmartList()

    override fun findPackageParts(packageFqName: String): List<String> {
        val rootToPackageParts = getPackageParts(packageFqName)
        if (rootToPackageParts.isEmpty()) return emptyList()

        val result = linkedSetOf<String>()
        val visitedMultifileFacades = linkedSetOf<String>()
        for (packageParts in rootToPackageParts) {
            for (name in packageParts.parts) {
                val facadeName = packageParts.getMultifileFacadeName(name)
                if (facadeName == null || facadeName !in visitedMultifileFacades) {
                    result.add(name)
                }
            }
            packageParts.parts.mapNotNullTo(visitedMultifileFacades, packageParts::getMultifileFacadeName)
        }
        return result.toList()
    }

    override fun findMetadataPackageParts(packageFqName: String): List<String> =
        getPackageParts(packageFqName).flatMap(PackageParts::metadataParts).distinct()

    @Synchronized
    private fun getPackageParts(packageFqName: String): List<PackageParts> =
        loadedModules.mapNotNull { (mapping, _) -> mapping.findPackageParts(packageFqName) }

    override fun getAnnotationsOnBinaryModule(moduleName: String): List<ClassId> {
        return loadedModules.mapNotNull { (mapping, name) ->
            if (name == moduleName) mapping.moduleData.annotations.map(ClassId::fromString) else null
        }.flatten()
    }

    fun addRoots(roots: List<File>, messageCollector: MessageCollector) {
        for (root in roots) {
            if (root.isDirectory) loadModulesFromClassesDir(root, messageCollector)
            else if (root.isDirectory && root.extension.equals("jar", ignoreCase = true)) loadModulesFromJar(root, messageCollector)
            else messageCollector.report(
                WARNING,
                "Unknown module type $root"
            )
        }
    }

    private fun loadModulesFromClassesDir(root: File, messageCollector: MessageCollector) {
        File(root, "META-INF").takeIf { it.exists() && it.isDirectory }?.let { metaInf ->
            metaInf.listFiles { _, name -> name?.endsWith(ModuleMapping.MAPPING_FILE_EXT) == true }?.forEach { moduleFile ->
                tryLoadModuleMapping(
                    moduleFile.readBytes(), moduleFile.path, moduleFile.path, deserializationConfiguration, messageCollector
                )?.let {
                    loadedModules.add(ModuleMappingInfo(it, moduleFile.nameWithoutExtension))
                }
            }
        }
    }

    private fun loadModulesFromJar(jar: File, messageCollector: MessageCollector) {
        JarInputStream(FileInputStream(jar)).use { jarInputStream ->
            do {
                val entry = jarInputStream.nextJarEntry
                if (entry != null) {
                    try {
                        if (!entry.isDirectory && entry.name.startsWith("META-INF/") && entry.name.endsWith(ModuleMapping.MAPPING_FILE_EXT)) {
                            tryLoadModuleMapping(
                                jarInputStream.readBytes(), "${jar}!${entry.name}", entry.name,
                                deserializationConfiguration, messageCollector
                            )?.let {
                                loadedModules.add(ModuleMappingInfo(it, entry.name.removePrefix("META-INF/").removeSuffix(".${ModuleMapping.MAPPING_FILE_EXT}")))
                            }
                        }
                    } finally {
                        jarInputStream.closeEntry()
                    }
                }
            } while (entry != null)
        }
    }

}

fun tryLoadModuleMapping(
    moduleBytes: ByteArray,
    debugName: String,
    modulePath: String,
    deserializationConfiguration: CompilerDeserializationConfiguration,
    messageCollector: MessageCollector
): ModuleMapping? = try {
    ModuleMapping.loadModuleMapping(moduleBytes, debugName, deserializationConfiguration) { incompatibleVersion ->
        messageCollector.report(
            ERROR,
            "Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is " +
                    "$incompatibleVersion, expected version is ${JvmMetadataVersion.INSTANCE}.",
            CompilerMessageLocation.create(modulePath)
        )
    }
} catch (e: EOFException) {
    messageCollector.report(
        ERROR, "Error occurred when reading the module: ${e.message}", CompilerMessageLocation.create(modulePath)
    )
    messageCollector.report(
        LOGGING,
        String(ByteArrayOutputStream().also { e.printStackTrace(PrintStream(it)) }.toByteArray()),
        CompilerMessageLocation.create(modulePath)
    )
    null
}
