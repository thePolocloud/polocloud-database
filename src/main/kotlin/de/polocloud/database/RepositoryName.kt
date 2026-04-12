package de.polocloud.database

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RepositoryName(val name: String)
