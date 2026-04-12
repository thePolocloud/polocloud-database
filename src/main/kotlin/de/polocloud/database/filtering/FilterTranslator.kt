package de.polocloud.database.filtering

interface FilterTranslator<Q> {
    fun translate(filter: Filter): Q
}