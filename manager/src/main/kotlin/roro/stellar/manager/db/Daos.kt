package roro.stellar.manager.db

import androidx.room.*

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands")
    suspend fun getAll(): List<CommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(commands: List<CommandEntity>)

    @Query("DELETE FROM commands")
    suspend fun deleteAll()
}

@Dao
interface LogDao {
    @Query("SELECT line FROM logs ORDER BY id DESC")
    fun getAll(): List<String>

    @Query("SELECT line FROM logs ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<String>

    @Insert
    fun insert(entity: LogEntity)

    @Query("DELETE FROM logs")
    fun deleteAll()
}

@Dao
interface ConfigDao {
    @Query("SELECT value FROM config WHERE `key` = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(entity: ConfigEntity)

    @Query("SELECT * FROM config")
    fun getAll(): List<ConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setAll(entities: List<ConfigEntity>)
}

@Dao
interface IntentDao {
    @Query("SELECT * FROM intents ORDER BY title ASC")
    suspend fun getAll(): List<IntentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(intents: List<IntentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intent: IntentEntity)

    @Update
    suspend fun update(intent: IntentEntity)

    @Query("DELETE FROM intents")
    suspend fun deleteAll()

    @Query("DELETE FROM intents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM intents WHERE id = :id")
    suspend fun getById(id: String): IntentEntity?
}