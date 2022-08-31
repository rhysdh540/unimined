package xyz.wagyourtail.unimined.providers.minecraft.patch

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.Constants
import xyz.wagyourtail.unimined.providers.minecraft.EnvType
import xyz.wagyourtail.unimined.providers.minecraft.MinecraftProvider
import java.nio.file.Path

abstract class AbstractMinecraftTransformer protected constructor(
    protected val project: Project,
    val provider: MinecraftProvider
) {

    internal val dynamicTransformerDependencies: Configuration = project.configurations.maybeCreate(Constants.DYNAMIC_TRANSFORMER_DEPENDENCIES)

    @ApiStatus.Internal
    abstract fun transform(envType: EnvType, baseMinecraft: Path): Path

    private fun applyRunConfigs(tasks: TaskContainer) {
        project.logger.warn("client: ${provider.client}, server: ${provider.server}")
        if (provider.minecraftDownloader.client) {
            applyClientRunConfig(tasks)
        }
        if (provider.minecraftDownloader.server) {
            applyServerRunConfig(tasks)
        }
    }

    protected open fun applyClientRunConfig(tasks: TaskContainer) {
        provider.provideRunClientTask(tasks) {  }
    }

    protected open fun applyServerRunConfig(tasks: TaskContainer) {
        provider.provideRunServerTask(tasks) {  }
    }

    @ApiStatus.Internal
    open fun afterEvaluate() {
        provider.parent.events.register(::sourceSets)
        provider.parent.events.register(::applyRunConfigs)
    }

    @ApiStatus.Internal
    open fun sourceSets(sourceSets: SourceSetContainer) {}

    @ApiStatus.Internal
    open fun afterRemap(envType: EnvType, namespace: String, baseMinecraft: Path): Path {
        return baseMinecraft
    }
}