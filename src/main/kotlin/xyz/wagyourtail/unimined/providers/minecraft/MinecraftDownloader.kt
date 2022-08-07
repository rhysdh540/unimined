package xyz.wagyourtail.unimined.providers.minecraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraftforge.artifactural.api.artifact.ArtifactIdentifier
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import xyz.wagyourtail.unimined.*
import xyz.wagyourtail.unimined.providers.minecraft.version.*
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class MinecraftDownloader(val provider: MinecraftProvider) {

    private val project: Project = provider.project
    private val METADATA_URL: URI = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")

    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    val client = !UniminedPlugin.getOptions(project).disableCombined.get() || sourceSets.findByName("client") != null
    val server = !UniminedPlugin.getOptions(project).disableCombined.get() || sourceSets.findByName("server") != null

    lateinit var versionData: VersionData
    val extractDependencies = mutableMapOf<Dependency, Extract>()
    val clientWorkingDir = project.projectDir.resolve("run").resolve("client")

    fun downloadMinecraft() {
        project.logger.info("Downloading Minecraft...")

        val dependencies = MinecraftProvider.getMinecraftProvider(project).combined.dependencies

        if (dependencies.isEmpty()) {
            throw IllegalStateException("No dependencies found for Minecraft")
        }

        if (dependencies.size > 1) {
            throw IllegalStateException("Multiple dependencies found for Minecraft")
        }

        val dependency = dependencies.first()


        if (dependency.group != Constants.MINECRAFT_GROUP) {
            throw IllegalArgumentException("Dependency $dependency is not Minecraft")
        }

        if (dependency.name != "minecraft") {
            throw IllegalArgumentException("Dependency $dependency is not a Minecraft dependency")
        }

        if (project.gradle.startParameter.isRefreshDependencies) {
            dependency.version?.let { clientJarDownloadPath(it).deleteIfExists() }
            dependency.version?.let { serverJarDownloadPath(it).deleteIfExists() }
            dependency.version?.let { combinedJarDownloadPath(it).deleteIfExists() }
            dependency.version?.let { combinedPomPath(it).deleteIfExists() }
        }

        // get mc version metadata
        val metadata = getMetadata()
        val version = getVersion(dependency.version!!, metadata)
        versionData = parseVersionData(
            if (version == null) {
                readVersionFile(dependency.version!!)
            } else {
                getVersionData(version)
            }
        )

        // get mc version jars
        val clientJar = versionData.downloads["client"]
        val serverJar = versionData.downloads["server"]

        // get download files
        val clientJarDownloadPath = clientJarDownloadPath(versionData.id)
        val serverJarDownloadPath = serverJarDownloadPath(versionData.id)
        val combinedPomPath = combinedPomPath(versionData.id)

        clientJarDownloadPath.parent.maybeCreate()
        serverJarDownloadPath.parent.maybeCreate()

        // initiate downloads
        if (client && clientJar != null) {
            download(clientJar, clientJarDownloadPath)
        } else if (client) {
            throw IllegalStateException("No client jar found for Minecraft")
        }
        if (server && serverJar != null) {
            download(serverJar, serverJarDownloadPath)
        } else if (server) {
            throw IllegalStateException("No server jar found for Minecraft")
        }

        // write pom
        Files.write(
            combinedPomPath,
            XMLBuilder("project").addStringOption(
                "xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"
            ).addStringOption("xmlns", "http://maven.apache.org/POM/4.0.0").append(
                XMLBuilder("modelVersion").append("4.0.0"),
                XMLBuilder("groupId").append("net.minecraft"),
                XMLBuilder("artifactId").append("minecraft"),
                XMLBuilder("version").append(versionData.id),
                XMLBuilder("packaging").append("jar"),
            ).toString().toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )

        project.repositories.maven {
            it.url = URI.create(Constants.MINECRAFT_MAVEN)
        }

        // add minecraft client/server deps
        if (client) {
            MinecraftProvider.getMinecraftProvider(project).client.dependencies.add(
                project.dependencies.create(
                    "net.minecraft:minecraft:${versionData.id}:client"
                )
            )
        }

        if (server) {
            MinecraftProvider.getMinecraftProvider(project).server.dependencies.add(
                project.dependencies.create(
                    "net.minecraft:minecraft:${versionData.id}:server"
                )
            )
        }

        if (UniminedPlugin.getOptions(project).disableCombined.get()) {
            MinecraftProvider.getMinecraftProvider(project).combined.let {
                it.dependencies.clear()
                if (client) {
                    it.dependencies.add(
                        project.dependencies.create(
                            "net.minecraft:minecraft:${versionData.id}:client"
                        )
                    )
                }
                if (server) {
                    it.dependencies.add(
                        project.dependencies.create(
                            "net.minecraft:minecraft:${versionData.id}:server"
                        )
                    )
                }
            }
        }
    }

    fun getMinecraft(dependency: ArtifactIdentifier): Path {

        if (dependency.group != Constants.MINECRAFT_GROUP) {
            throw IllegalArgumentException("Dependency $dependency is not Minecraft")
        }

        if (dependency.name != "minecraft") {
            throw IllegalArgumentException("Dependency $dependency is not a Minecraft dependency")
        }

        if (dependency.extension == "pom") {
            return combinedPomPath(dependency.version)
        } else if (dependency.extension == "jar") {
            //TODO: combine jars
            // v1.2.5+ are combined anyway so...
            return if (dependency.classifier == null || dependency.classifier == "client") {
                clientJarDownloadPath(dependency.version)
            } else if (dependency.classifier == "server") {
                serverJarDownloadPath(dependency.version)
            } else {
                throw IllegalArgumentException("Unknown classifier ${dependency.classifier}")
            }
        }
        throw IllegalStateException("Unknown dependency extension ${dependency.extension}")
    }

    private fun getMetadata(): JsonObject? {

        if (project.gradle.startParameter.isOffline) {
            return null
        }

        val urlConnection = METADATA_URL.toURL().openConnection() as HttpURLConnection
        urlConnection.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)")
        urlConnection.requestMethod = "GET"
        urlConnection.connect()

        if (urlConnection.responseCode != 200) {
            throw Exception("Failed to get metadata, ${urlConnection.responseCode}: ${urlConnection.responseMessage}")
        }

        val json = urlConnection.inputStream.use {
            InputStreamReader(it).use { reader ->
                JsonParser.parseReader(reader).asJsonObject
            }
        }

        return json
    }

    private fun getVersion(versionId: String, metadata: JsonObject?): JsonObject? {
        if (metadata == null) {
            return null
        }

        val versions = metadata.getAsJsonArray("versions") ?: throw Exception("Failed to get metadata, no versions")

        for (version in versions) {
            val versionObject = version.asJsonObject
            val id = versionObject.get("id").asString
            if (id == versionId) {
                return versionObject
            }
        }
        throw Exception("Failed to get metadata, no version found for $versionId")
    }

    private fun getVersionData(version: JsonObject): JsonObject {
        val downloadPath = versionJsonDownloadPath(version.get("id").asString)

        if (project.gradle.startParameter.isRefreshDependencies) {
            downloadPath.deleteIfExists()
        }

        if (!downloadPath.exists() || !testSha1(-1, version.get("sha1").asString, downloadPath)) {

            if (project.gradle.startParameter.isOffline) {
                throw Exception("Failed to get version, offline mode")
            }

            val url = version.get("url").asString
            val urlConnection = URI.create(url).toURL().openConnection() as HttpURLConnection
            urlConnection.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)")
            urlConnection.requestMethod = "GET"
            urlConnection.connect()

            if (urlConnection.responseCode != 200) {
                throw Exception("Failed to get version data, ${urlConnection.responseCode}: ${urlConnection.responseMessage}")
            }

            urlConnection.inputStream.use {
                Files.write(downloadPath, it.readBytes())
            }
        }
        if (!testSha1(-1, version.get("sha1").asString, downloadPath)) {
            throw Exception("Failed to get version, checksum mismatch")
        }
        return readVersionFile(version.get("id").asString)
    }

    private fun readVersionFile(versionId: String): JsonObject {
        val downloadPath = versionJsonDownloadPath(versionId)

        if (!downloadPath.exists()) {
            if (project.gradle.startParameter.isOffline) {
                throw Exception("Failed to get version, offline mode")
            }
            throw Exception("Failed to get version, file not found.")
        }

        return InputStreamReader(downloadPath.inputStream()).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
    }

    private fun download(download: Download, path: Path) {

        if (testSha1(download.size, download.sha1, path)) {
            return
        }

        download.url?.toURL()?.openStream()?.use {
            Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
        }

        if (!testSha1(download.size, download.sha1, path)) {
            throw Exception("Failed to download " + download.url)
        }
    }

    private fun download(artifact: Artifact, path: Path) {

        if (testSha1(artifact.size, artifact.sha1 ?: "", path)) {
            return
        }

        artifact.url?.toURL()?.openStream()?.use {
            Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
        }

        if (!testSha1(artifact.size, artifact.sha1 ?: "", path)) {
            throw Exception("Failed to download " + artifact.url)
        }
    }

    private fun download(url: URI, path: Path) {
        url.toURL().openStream().use {
            Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun extract(dependency: Dependency, extract: Extract, path: Path) {
        val resolved = MinecraftProvider.getMinecraftProvider(project).mcLibraries.resolvedConfiguration
        resolved.getFiles { it == dependency }.forEach { file ->
            ZipInputStream(file.inputStream()).use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        entry = stream.nextEntry
                        continue
                    }
                    if (extract.exclude.any { entry.name.startsWith(it) }) {
                        entry = stream.nextEntry
                        continue
                    }
                    Files.copy(stream, path.resolve(entry.name), StandardCopyOption.REPLACE_EXISTING)
                    entry = stream.nextEntry
                }
            }
        }
    }

    fun clientJarDownloadPath(version: String): Path {
        return UniminedPlugin.getGlobalCache(project)
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
            .resolve("client.jar")
    }

    fun serverJarDownloadPath(version: String): Path {
        return UniminedPlugin.getGlobalCache(project)
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
            .resolve("server.jar")
    }

    fun combinedPomPath(version: String): Path {
        return UniminedPlugin.getGlobalCache(project)
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
            .resolve("minecraft-$version.xml")
    }

    fun combinedJarDownloadPath(version: String): Path {
        return UniminedPlugin.getGlobalCache(project)
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
            .resolve("minecraft-$version.jar")
    }

    fun versionJsonDownloadPath(version: String): Path {
        return UniminedPlugin.getGlobalCache(project)
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
            .resolve("version.json")
    }
}