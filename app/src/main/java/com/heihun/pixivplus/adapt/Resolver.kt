package com.heihun.pixivplus.adapt

interface Resolver {

    fun name(): String

    fun resolve(spec: ResolveSpec): ResolveStatus
}
