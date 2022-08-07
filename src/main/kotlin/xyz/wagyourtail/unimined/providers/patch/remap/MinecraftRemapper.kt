package xyz.wagyourtail.unimined.providers.patch.remap

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.minecraftforge.artifactural.api.artifact.ArtifactIdentifier
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.Constants
import xyz.wagyourtail.unimined.providers.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.providers.patch.AbstractMinecraftTransformer
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Suppress("MemberVisibilityCanBePrivate")
class MinecraftRemapper(
    val project: Project,
    val provider: MinecraftProvider,
) {

    //TODO: make this configurable
    val remapFrom = "obf"
    val fallbackTarget = "intermediary"

    val mappings: Configuration = project.configurations.maybeCreate(Constants.MAPPINGS_PROVIDER)

    lateinit var mappingTree: MemoryMappingTree

    private fun getMappingsFiles(): Set<File> {
        val dependencies = mappings.dependencies

        if (dependencies.isEmpty()) {
            throw IllegalStateException("No dependencies found for mappings provider")
        }

        return mappings.resolve()
    }

    private fun forEachInZip(zip: File, action: (InputStream) -> Unit) {
        ZipInputStream(zip.inputStream()).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    entry = stream.nextEntry
                    continue
                }
                if (entry.name.contains("META-INF") || entry.name.contains("extras")) {
                    entry = stream.nextEntry
                    continue
                }
                project.logger.info("Reading ${entry.name}")
                action(stream)
                entry = stream.nextEntry
            }
        }
    }

    private fun memberOf(className: String, memberName: String, descriptor: String): IMappingProvider.Member {
        return IMappingProvider.Member(className, memberName, descriptor)
    }

    fun getMappingProvider(
        srcName: String,
        fallbackTarget: String,
        targetName: String,
        remapLocalVariables: Boolean = true
    ): (MappingAcceptor) -> Unit {
        mappingTree = MemoryMappingTree()
        for (file in getMappingsFiles()) {
            forEachInZip(file) { stream ->
                MappingReader.read(InputStreamReader(stream), mappingTree)
            }
        }
        return { acceptor: MappingAcceptor ->
            val fromId = mappingTree.getNamespaceId(srcName)
            var fallbackId = mappingTree.getNamespaceId(fallbackTarget)
            val toId = mappingTree.getNamespaceId(targetName)

            if (toId == MappingTreeView.NULL_NAMESPACE_ID) {
                throw RuntimeException("Namespace $targetName not found in mappings")
            }

            if (fallbackId == MappingTreeView.NULL_NAMESPACE_ID) {
                fallbackId = fromId
            }

            @Suppress("INACCESSIBLE_TYPE")
            for (classDef in mappingTree.classes as Collection<ClassMapping>) {
                val fromClassName = classDef.getName(fromId)
                val toClassName = classDef.getName(toId) ?: classDef.getName(fallbackId) ?: fromClassName

                acceptor.acceptClass(fromClassName, toClassName)

                for (fieldDef in classDef.fields) {
                    val fromFieldName = fieldDef.getName(fromId)
                    val toFieldName = fieldDef.getName(toId) ?: fieldDef.getName(fallbackId) ?: fromFieldName

                    acceptor.acceptField(memberOf(fromClassName, fromFieldName, fieldDef.getDesc(fromId)), toFieldName)
                }

                for (methodDef in classDef.methods) {
                    val fromMethodName = methodDef.getName(fromId)
                    val toMethodName = methodDef.getName(toId) ?: methodDef.getName(fallbackId) ?: fromMethodName
                    val fromMethodDesc = methodDef.getDesc(fromId)

                    val method = memberOf(fromClassName, fromMethodName, fromMethodDesc)

                    acceptor.acceptMethod(method, toMethodName)

                    if (remapLocalVariables) {
                        for (arg in methodDef.args) {
                            val toArgName = arg.getName(toId) ?: arg.getName(fallbackId) ?: continue
                            acceptor.acceptMethodArg(method, arg.lvIndex, toArgName)
                        }

                        for (localVar in methodDef.vars) {
                            val toLocalVarName = localVar.getName(toId) ?: localVar.getName(fallbackId) ?: continue
                            acceptor.acceptMethodVar(
                                method,
                                localVar.lvIndex,
                                localVar.startOpIdx,
                                localVar.lvtRowIndex,
                                toLocalVarName
                            )
                        }
                    }
                }
            }
        }
    }


    fun provide(file: Path, remapTo: String, remapFrom: String = this.remapFrom): Path {

        val mappingsDependecies = (mappings.dependencies as Set<Dependency>).sortedBy { "${it.name}-${it.version}" }
        val combinedNames = mappingsDependecies.joinToString("+") { it.name + "-" + it.version }

        val parent = file.parent
        val target = parent.resolve(combinedNames)
            .resolve("${file.nameWithoutExtension}-mapped-${combinedNames}-${remapTo}.${file.extension}")

        if (target.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            return target
        }

        val remapper = TinyRemapper.newRemapper().withMappings(getMappingProvider(remapFrom, fallbackTarget, remapTo))
            .renameInvalidLocals(true)
            .inferNameFromSameLvIndex(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .checkPackageAccess(true)
            .fixPackageAccess(true)
            .rebuildSourceFilenames(true)
            .build()


        OutputConsumerPath.Builder(target).build().use {
            it.addNonClassFiles(file, NonClassCopyMode.FIX_META_INF, null)
            remapper.readInputs(file)
            remapper.apply(it)
        }
        remapper.finish()
        return target
    }
}