package roro.stellar.manager.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CommandEntity::class, 
        ConfigEntity::class, 
        LogEntity::class,
        IntentEntity::class
    ], 
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun configDao(): ConfigDao
    abstract fun logDao(): LogDao
    abstract fun intentDao(): IntentDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            val deviceContext = context.applicationContext.createDeviceProtectedStorageContext()
            runCatching { deviceContext.moveDatabaseFrom(context.applicationContext, DATABASE_NAME) }
            instance ?: Room.databaseBuilder(deviceContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()  // 开发阶段清空数据重建
                .build().also { instance = it }
        }

        private const val DATABASE_NAME = "stellar.db"
    }
}