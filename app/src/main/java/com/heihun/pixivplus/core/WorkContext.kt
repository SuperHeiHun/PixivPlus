package com.heihun.pixivplus.core

data class WorkSnapshot(
    val id: Long,
    val type: String,
    val title: String?,
    val authorId: Long? = null,
    val authorName: String?,
    val authorAccount: String?,
    val tags: List<String>
)

object WorkContext {

    @Volatile
    var current: WorkSnapshot? = null

    fun update(snapshot: WorkSnapshot?) {
        if (snapshot == null) {
            current = null
            return
        }
        val previous = current
        current = if (previous == null || previous.id != snapshot.id) {
            snapshot
        } else {
            WorkSnapshot(
                id = snapshot.id,
                type = snapshot.type,
                title = snapshot.title ?: previous.title,
                authorId = snapshot.authorId ?: previous.authorId,
                authorName = snapshot.authorName ?: previous.authorName,
                authorAccount = snapshot.authorAccount ?: previous.authorAccount,
                tags = snapshot.tags.ifEmpty { previous.tags }
            )
        }
    }

    fun clear() {
        current = null
    }
}
