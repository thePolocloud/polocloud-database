package de.polocloud.database

object DatabaseAccess {

    private lateinit var connection : DatabaseConnectionFactory<*>;

    fun initialize(credentials : DatabaseCredentials) {
        this.connection = credentials.factory()
    }

    fun connect() : Boolean {
        this.connection.connect()
        return this.connection.isValid();
    }

    fun executor() = this.connection.executor()

    fun close() {
        this.connection.close()
    }
}