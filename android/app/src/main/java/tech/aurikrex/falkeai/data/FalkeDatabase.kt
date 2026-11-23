package tech.aurikrex.falkeai.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "falke_alarms")
data class FalkeAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** Bitmask: Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64. 0 = one-shot. */
    val daysMask: Int = 0,
    val enabled: Boolean = true,
    val nextTriggerAtMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "captured_notifications")
data class CapturedNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val bodyRedacted: String,
    val isSocial: Boolean = false,
    val postedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_callers")
data class CachedCallerEntity(
    @PrimaryKey val phone: String,
    val displayName: String? = null,
    val relationship: String? = null,
    val tier: String = "normal",
    val summaryLine: String = "",
    val cachedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface FalkeAlarmDao {
    @Query("SELECT * FROM falke_alarms ORDER BY hour ASC, minute ASC")
    fun observeAll(): Flow<List<FalkeAlarmEntity>>

    @Query("SELECT * FROM falke_alarms ORDER BY hour ASC, minute ASC")
    suspend fun getAll(): List<FalkeAlarmEntity>

    @Query("SELECT * FROM falke_alarms WHERE enabled = 1 ORDER BY nextTriggerAtMs ASC")
    suspend fun getEnabled(): List<FalkeAlarmEntity>

    @Query("SELECT * FROM falke_alarms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FalkeAlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: FalkeAlarmEntity): Long

    @Update
    suspend fun update(alarm: FalkeAlarmEntity)

    @Query("DELETE FROM falke_alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CapturedNotificationDao {
    @Query("SELECT * FROM captured_notifications ORDER BY postedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 80): Flow<List<CapturedNotificationEntity>>

    @Query("SELECT * FROM captured_notifications ORDER BY postedAtMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<CapturedNotificationEntity>

    @Query(
        "SELECT * FROM captured_notifications WHERE LOWER(title) LIKE '%' || LOWER(:query) || '%' " +
            "OR LOWER(bodyRedacted) LIKE '%' || LOWER(:query) || '%' " +
            "OR LOWER(appLabel) LIKE '%' || LOWER(:query) || '%' ORDER BY postedAtMs DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 25): List<CapturedNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CapturedNotificationEntity): Long

    @Query("DELETE FROM captured_notifications WHERE id NOT IN (SELECT id FROM captured_notifications ORDER BY postedAtMs DESC LIMIT 300)")
    suspend fun pruneOld()
}

@Dao
interface CachedCallerDao {
    @Query("SELECT * FROM cached_callers WHERE phone = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): CachedCallerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(caller: CachedCallerEntity)
}

@Database(
    entities = [
        FalkeAlarmEntity::class,
        CapturedNotificationEntity::class,
        CachedCallerEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FalkeDatabase : RoomDatabase() {
    abstract fun alarmDao(): FalkeAlarmDao
    abstract fun notificationDao(): CapturedNotificationDao
    abstract fun callerDao(): CachedCallerDao

    companion object {
        @Volatile
        private var INSTANCE: FalkeDatabase? = null

        fun getInstance(context: Context): FalkeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FalkeDatabase::class.java,
                    "falkeai_companion.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
