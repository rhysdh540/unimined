package xyz.wagyourtail.unimined.internal.mods.task

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mod.task.GenModSourcesTask
import xyz.wagyourtail.unimined.internal.mods.ModsProvider
import java.io.File
import javax.inject.Inject

abstract class GenModSourcesTaskImpl @Inject constructor(@get:Internal val provider: ModsProvider) : GenModSourcesTask() {

    @TaskAction
    fun run() {
        println(provider.remapConfigsResolved)
        provider.remapConfigsResolved.values.flatMap { it.configurations }.flatMap { it.resolve() }.forEach {
            genSources(it)
        }
    }

    private fun genSources(jar: File) {
        provider.minecraft.sourceProvider.sourceGenerator.generate(
            provider.minecraft.sourceSet.compileClasspath,
            jar.toPath(),
            jar.toPath().resolveSibling("${jar.nameWithoutExtension}-sources.jar"),
            jar.toPath().resolveSibling("${jar.nameWithoutExtension}-linemapped.jar")
        )
    }

}