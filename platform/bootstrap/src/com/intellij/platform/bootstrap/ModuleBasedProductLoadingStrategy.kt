// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.bootstrap

import com.intellij.ide.plugins.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.runtime.repository.ProductMode
import com.intellij.platform.runtime.repository.RuntimeModuleGroup
import com.intellij.platform.runtime.repository.RuntimeModuleId
import com.intellij.platform.runtime.repository.RuntimeModuleRepository
import com.intellij.platform.runtime.repository.serialization.RuntimeModuleRepositorySerialization
import com.intellij.util.lang.PathClassLoader
import com.intellij.util.lang.ZipFilePool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.nio.file.Files
import java.nio.file.Path

class ModuleBasedProductLoadingStrategy(internal val moduleRepository: RuntimeModuleRepository) : ProductLoadingStrategy() {
  private val productModules by lazy {
    val rootModuleName = System.getProperty(PLATFORM_ROOT_MODULE_PROPERTY)
    if (rootModuleName == null) {
      error("'$PLATFORM_ROOT_MODULE_PROPERTY' system property is not specified")
    }
    val currentModeId = System.getProperty(PLATFORM_PRODUCT_MODE_PROPERTY, ProductMode.LOCAL_IDE.id)
    val currentMode = ProductMode.entries.find { it.id == currentModeId}
    if (currentMode == null) {
      error("Unknown mode '$currentModeId' specified in '$PLATFORM_PRODUCT_MODE_PROPERTY' system property")
    }

    val rootModule = moduleRepository.getModule(RuntimeModuleId.module(rootModuleName))
    val productModulesPath = "META-INF/$rootModuleName/product-modules.xml"
    val moduleGroupStream = rootModule.readFile(productModulesPath)
    if (moduleGroupStream == null) {
      error("$productModulesPath is not found in '$rootModuleName' module")
    }
    RuntimeModuleRepositorySerialization.loadProductModules(moduleGroupStream, productModulesPath, currentMode, moduleRepository)
  }
  
  override fun addMainModuleGroupToClassPath(bootstrapClassLoader: ClassLoader) {
    val mainGroupClassPath = productModules.mainModuleGroup.includedModules.flatMapTo(LinkedHashSet()) { 
      it.moduleDescriptor.resourceRootPaths 
    }
    val classPath = (bootstrapClassLoader as PathClassLoader).classPath
    logger<ModuleBasedProductLoadingStrategy>().info("New classpath roots:\n${(mainGroupClassPath - classPath.baseUrls.toSet()).joinToString("\n")}")
    classPath.addFiles(mainGroupClassPath)
  }

  override fun loadBundledPluginDescriptors(scope: CoroutineScope,
                                            bundledPluginDir: Path?,
                                            isUnitTestMode: Boolean,
                                            context: DescriptorListLoadingContext,
                                            zipFilePool: ZipFilePool?): List<Deferred<IdeaPluginDescriptorImpl?>> {
    val mainGroupModules = productModules.mainModuleGroup.includedModules.map { it.moduleDescriptor.moduleId }
    return productModules.bundledPluginModuleGroups.map { moduleGroup ->
      scope.async {
        if (moduleGroup.includedModules.none { it.moduleDescriptor.moduleId in mainGroupModules }) {
          loadPluginDescriptorFromRuntimeModule(moduleGroup, context, zipFilePool)
        }
        else {
          /* todo: intellij.performanceTesting.async plugin has different distributions for different IDEs, in some IDEs it has dependencies 
             on 'intellij.profiler.common' and other module from the platform, in other IDEs it includes them as its own content. In the
             latter case we currently cannot run it using the modular loader, because these modules will be loaded twice. */
          logger<ModuleBasedProductLoadingStrategy>().debug("Skipped $moduleGroup: ${moduleGroup.includedModules}")
          null
        }
      }
    }
  }
  
  private fun loadPluginDescriptorFromRuntimeModule(pluginModuleGroup: RuntimeModuleGroup,
                                                    context: DescriptorListLoadingContext,
                                                    zipFilePool: ZipFilePool?): IdeaPluginDescriptorImpl? {
    val resourceRoots = pluginModuleGroup.includedModules.flatMapTo(LinkedHashSet()) { it.moduleDescriptor.resourceRootPaths }
    val resourceRoot = resourceRoots.singleOrNull()  
                       ?: error("Plugins with multiple resource roots aren't supported for now, so '${pluginModuleGroup}' ($resourceRoots) cannot be loaded")
    return if (Files.isDirectory(resourceRoot)) {
      loadDescriptorFromDir(
        file = resourceRoot,
        descriptorRelativePath = PluginManagerCore.PLUGIN_XML_PATH,
        pluginPath = resourceRoot,
        context = context,
        isBundled = true,
        isEssential = false,
        useCoreClassLoader = false,
        pathResolver = PluginXmlPathResolver.DEFAULT_PATH_RESOLVER
      )
    }
    else {
      loadDescriptorFromJar(
        file = resourceRoot,
        fileName = PluginManagerCore.PLUGIN_XML,
        pathResolver = PluginXmlPathResolver.DEFAULT_PATH_RESOLVER,
        parentContext = context,
        isBundled = true,
        isEssential = false,
        useCoreClassLoader = false,
        pluginPath = resourceRoot.parent.parent,
        pool = zipFilePool
      ).also {
        it?.jarFiles = listOf(resourceRoot)
      }
    }
  }

  override val shouldLoadDescriptorsFromCoreClassPath: Boolean
    get() = false

  override fun isOptionalProductModule(moduleName: String): Boolean {
    return productModules.mainModuleGroup.optionalModuleIds.contains(RuntimeModuleId.raw(moduleName))
  }
}

private const val PLATFORM_ROOT_MODULE_PROPERTY = "intellij.platform.root.module"
private const val PLATFORM_PRODUCT_MODE_PROPERTY = "intellij.platform.product.mode"