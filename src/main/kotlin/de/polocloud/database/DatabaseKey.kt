package de.polocloud.database

import kotlin.reflect.KClass

data class DatabaseKey<T : Any>(val clazz: KClass<T>) {

    fun id(): String {
        val repoAnnotation = clazz.annotations.filterIsInstance<RepositoryName>().firstOrNull()
        return repoAnnotation?.name ?: clazz.simpleName!!
    }
}