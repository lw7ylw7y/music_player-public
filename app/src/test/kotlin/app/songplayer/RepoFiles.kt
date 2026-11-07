package app.songplayer

import java.io.File

/** Locates the repository (the build passes `repoRoot`) for the source-scan and manifest rules. */
object RepoFiles {
    val root: File = File(requireNotNull(System.getProperty("repoRoot")) { "repoRoot is not set" })

    fun kotlinSources(module: String): List<File> =
        File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * The merged manifest of the release build, written by `:app:processReleaseMainManifest`.
     * AGP has used both `merged_manifest` and `merged_manifests` as the folder name, so match either;
     * when several match, prefer the output of the main-manifest merge. On failure the message lists
     * every manifest found, so a changed AGP layout is easy to see.
     */
    fun mergedReleaseManifest(): File {
        val dir = File(root, "app/build/intermediates")
        val all = dir.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
        val candidates = all.filter {
            val relative = it.relativeTo(dir).path
            relative.contains("merged_manifest") && relative.contains("release", ignoreCase = true)
        }
        val chosen = candidates.singleOrNull()
            ?: candidates.singleOrNull { it.parentFile.name == "processReleaseMainManifest" }
        check(chosen != null) {
            "Expected one merged release manifest under $dir, matched ${candidates.size}. " +
                "Run :app:processReleaseMainManifest first. Manifests found: " +
                all.map { it.relativeTo(dir).path }
        }
        return chosen
    }
}
