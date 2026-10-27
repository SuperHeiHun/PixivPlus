package com.heihun.pixivplus.core.util

object EntityReader {

    data class AuthorInfo(
        val id: Long?,
        val name: String?,
        val account: String?,
        val avatarUrl: String?
    )

    fun title(entity: Any): String? {
        return string(entity, "getTitle")
    }

    fun type(entity: Any, fallback: String): String {
        val raw = ReflectUtil.readField(entity, "type") as? String ?: return fallback
        return when (raw.lowercase()) {
            "manga" -> "MANGA"
            "novel" -> "NOVEL"
            "illust" -> "ILLUST"
            else -> fallback
        }
    }

    fun user(entity: Any): Any? {
        return ReflectUtil.invoke(entity, ReflectUtil.findMethod(entity.javaClass, "getUser"))
    }

    fun authorName(entity: Any): String? {
        val user = user(entity) ?: return null
        return (ReflectUtil.readField(user, "name") as? String)?.ifEmpty { null }
    }

    fun authorAccount(entity: Any): String? {
        val user = user(entity) ?: return null
        return (ReflectUtil.readField(user, "account") as? String)?.ifEmpty { null }
    }

    fun authorId(entity: Any): Long? {
        val user = user(entity) ?: return null
        return ReflectUtil.readField(user, "id") as? Long
    }

    fun authorInfo(entity: Any): AuthorInfo? {
        val user = user(entity) ?: return null
        val id = ReflectUtil.readField(user, "id") as? Long
        val name = (ReflectUtil.readField(user, "name") as? String)?.ifEmpty { null }
        val account = (ReflectUtil.readField(user, "account") as? String)?.ifEmpty { null }
        val avatar = avatar(user)
        if (id == null && name == null && avatar == null) return null
        return AuthorInfo(id = id, name = name, account = account, avatarUrl = avatar)
    }

    private fun avatar(user: Any): String? {
        val urls = ReflectUtil.readField(user, "profileImageUrls") ?: return null
        val method = ReflectUtil.findMethod(urls.javaClass, "getMedium") ?: return null
        return (ReflectUtil.invoke(urls, method) as? String)?.ifEmpty { null }
    }

    fun caption(entity: Any): String? {
        return (ReflectUtil.readField(entity, "caption") as? String)?.ifEmpty { null }
    }

    fun pageCount(entity: Any): Int {
        return ReflectUtil.readField(entity, "pageCount") as? Int ?: 0
    }

    fun width(entity: Any): Int {
        return ReflectUtil.readField(entity, "width") as? Int ?: 0
    }

    fun height(entity: Any): Int {
        return ReflectUtil.readField(entity, "height") as? Int ?: 0
    }

    fun originalUrl(entity: Any): String? {
        val singlePage = ReflectUtil.readField(entity, "metaSinglePage") ?: return null
        val method = ReflectUtil.findMethod(singlePage.javaClass, "getOriginalImageUrl") ?: return null
        return (ReflectUtil.invoke(singlePage, method) as? String)?.ifEmpty { null }
    }

    fun pageImages(entity: Any): List<PageImage> {
        val pages = ReflectUtil.readField(entity, "metaPages") as? List<*> ?: return emptyList()
        val result = ArrayList<PageImage>()
        for (page in pages) {
            if (page == null) continue
            val urls = ReflectUtil.invoke(page, ReflectUtil.findMethod(page.javaClass, "getImageUrls")) ?: continue
            val original = stringValue(urls, "getOriginal")
            val large = stringValue(urls, "getLarge")
            val medium = stringValue(urls, "getMedium")
            if (original == null && large == null && medium == null) continue
            result.add(PageImage(original = original, large = large, medium = medium, width = 0, height = 0))
        }
        return result
    }

    private fun stringValue(target: Any, getter: String): String? {
        val method = ReflectUtil.findMethod(target.javaClass, getter) ?: return null
        return (ReflectUtil.invoke(target, method) as? String)?.ifEmpty { null }
    }

    fun tags(entity: Any): List<String> {
        val list = ReflectUtil.invoke(entity, ReflectUtil.findMethod(entity.javaClass, "getTags")) as? List<*>
            ?: return emptyList()
        val names = ArrayList<String>()
        for (tag in list) {
            if (tag == null) continue
            val name = string(tag, "getName") ?: continue
            names.add(name)
        }
        return names
    }

    fun thumbnail(entity: Any): String? {
        val urls = ReflectUtil.invoke(entity, ReflectUtil.findMethod(entity.javaClass, "getImageUrls"))
        if (urls != null) {
            for (getter in listOf("getMedium", "getSquareMedium", "getLarge")) {
                val value = ReflectUtil.invoke(urls, ReflectUtil.findMethod(urls.javaClass, getter)) as? String
                if (!value.isNullOrEmpty()) return value
            }
        }
        val metaPages = ReflectUtil.readField(entity, "metaPages") as? List<*> ?: return singlePage(entity)
        for (page in metaPages) {
            if (page == null) continue
            val pageUrls = ReflectUtil.invoke(page, ReflectUtil.findMethod(page.javaClass, "getImageUrls")) ?: continue
            for (getter in listOf("getMedium", "getOriginal", "getLarge")) {
                val value = ReflectUtil.invoke(pageUrls, ReflectUtil.findMethod(pageUrls.javaClass, getter)) as? String
                if (!value.isNullOrEmpty()) return value
            }
        }
        return singlePage(entity)
    }

    private fun singlePage(entity: Any): String? {
        val singlePage = ReflectUtil.readField(entity, "metaSinglePage") ?: return null
        val method = ReflectUtil.findMethod(singlePage.javaClass, "getOriginalImageUrl") ?: return null
        val value = ReflectUtil.invoke(singlePage, method) as? String
        return value?.ifEmpty { null }
    }

    private fun string(entity: Any, getter: String): String? {
        val method = ReflectUtil.findMethod(entity.javaClass, getter) ?: return null
        val value = ReflectUtil.invoke(entity, method) as? String ?: return null
        return value.ifEmpty { null }
    }
}
