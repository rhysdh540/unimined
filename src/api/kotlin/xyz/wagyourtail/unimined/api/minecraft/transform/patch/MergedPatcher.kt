package xyz.wagyourtail.unimined.api.minecraft.transform.patch

import xyz.wagyourtail.unimined.api.mappings.MappingNamespace
import xyz.wagyourtail.unimined.api.minecraft.PatchProviders

/**
 * @since 0.4.10
 */
interface MergedPatcher: MinecraftPatcher, PatchProviders {
    override var prodNamespace: MappingNamespace

    fun setProdNamespace(namespace: String) {
        prodNamespace = MappingNamespace.getNamespace(namespace)
    }
}