package de.polocloud.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Serializer for database objects using kotlinx.serialization.
 *
 * Only supports classes annotated with [kotlinx.serialization.Serializable].
 * Classes without @Serializable will throw an exception at runtime.
 */
object DatabaseSerializer {

    /** Json configuration for serialization/deserialization */
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * Serializes an object to JSON string.
     *
     * @param value the object to serialize
     * @return JSON string
     */
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> serialize(value: T, clazz: KClass<T>): String {
        val serializer = clazz.serializer()
        return json.encodeToString(serializer, value)
    }


    /**
     * Deserializes a JSON string into an object of the given class.
     *
     * @param jsonStr the JSON string
     * @param clazz the target class (must be annotated with @Serializable)
     * @return deserialized object
     * @throws [kotlinx.serialization.SerializationException] if the class is not @Serializable
     */
    fun <T : Any> deserialize(jsonStr: String, clazz: KClass<T>): T {
        val serializer = serializerFor(clazz)
        return json.decodeFromString(serializer, jsonStr)
    }

    /**
     * Utility to get a KSerializer for a given KClass.
     *
     * @param kClass the KClass to get serializer for
     * @return KSerializer instance
     * @throws [kotlinx.serialization.SerializationException] if the class is not @Serializable
     */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> serializerFor(kClass: KClass<T>): KSerializer<T> {
        return kClass.serializer()
    }
}
