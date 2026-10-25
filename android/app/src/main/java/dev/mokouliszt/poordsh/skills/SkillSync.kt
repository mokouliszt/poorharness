package dev.mokouliszt.poordsh.skills

/**
 * Where the user's skills are in every place a chat can run ([FolderSync]):
 * opencode's PRoot binds the store here, and each chat's sandbox gets a copy
 * here before every turn.
 */
object SkillSync {
    const val ROOT = "/tmp/poordsh/skills"
    val folders = FolderSync(ROOT)

    /** Brings [target] up to [snapshot]; true if anything was written. */
    fun sync(store: SkillStore, snapshot: FolderSync.Snapshot, target: FolderSync.Target): Boolean =
        folders.sync(snapshot, store::file, target)
}
