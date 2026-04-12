package de.polocloud.database

import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class EntryRef(val clazz : KClass<*>)
