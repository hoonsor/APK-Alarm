package com.hoonsor.alarm.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<AlarmEntity>

    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(alarms: List<AlarmEntity>)

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE alarms SET groupId = :groupId WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: Long?)

    @Query("UPDATE alarms SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroup(groupId: Long)

    @Query("DELETE FROM alarms")
    suspend fun deleteAll()

    /** 只更新語音快取欄位，避免覆蓋使用者同時進行的編輯 */
    @Query("UPDATE alarms SET ttsCachePath = :path, ttsCacheKey = :key WHERE id = :id")
    suspend fun setTtsCache(id: Long, path: String?, key: String?)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder, createdAt, id")
    fun observeAll(): Flow<List<AlarmGroupEntity>>

    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder, createdAt, id")
    suspend fun getAll(): List<AlarmGroupEntity>

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    suspend fun get(id: Long): AlarmGroupEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM alarm_groups")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(group: AlarmGroupEntity): Long

    @Update
    suspend fun update(group: AlarmGroupEntity)

    @Query("DELETE FROM alarm_groups")
    suspend fun deleteAll()

    @Query("DELETE FROM alarm_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers")
    suspend fun getAll(): List<TimerEntity>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun get(id: Long): TimerEntity?

    @Query("SELECT * FROM timers WHERE groupId = :groupId")
    suspend fun getByGroup(groupId: Long): List<TimerEntity>

    @Query("UPDATE timers SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroup(groupId: Long)

    @Query("DELETE FROM timers")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(timer: TimerEntity): Long

    @Update
    suspend fun update(timer: TimerEntity)

    @Delete
    suspend fun delete(timer: TimerEntity)
}

class Converters {
    @TypeConverter
    fun fromState(s: TimerState): String = s.name

    @TypeConverter
    fun toState(s: String): TimerState = runCatching { TimerState.valueOf(s) }.getOrDefault(TimerState.PAUSED)
}

@Dao
interface TimerGroupDao {
    @Query("SELECT * FROM timer_groups ORDER BY sortOrder, createdAt, id")
    fun observeAll(): Flow<List<TimerGroupEntity>>

    @Query("SELECT * FROM timer_groups ORDER BY sortOrder, createdAt, id")
    suspend fun getAll(): List<TimerGroupEntity>

    @Query("SELECT * FROM timer_groups WHERE id = :id")
    suspend fun get(id: Long): TimerGroupEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM timer_groups")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(group: TimerGroupEntity): Long

    @Update
    suspend fun update(group: TimerGroupEntity)

    @Query("DELETE FROM timer_groups")
    suspend fun deleteAll()

    @Query("DELETE FROM timer_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [AlarmEntity::class, TimerEntity::class, AlarmGroupEntity::class, TimerGroupEntity::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun timerDao(): TimerDao
    abstract fun groupDao(): GroupDao
    abstract fun timerGroupDao(): TimerGroupDao

    companion object {
        /** v0.1 → v0.2：新增群組資料表、鬧鐘加上群組與鈴聲欄位。既有鬧鐘全部保留。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `groupId` INTEGER")
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `soundUri` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alarm_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`expanded` INTEGER NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /** v0.2 → v0.3：個別音量、震動開關、語音播報與語音快取 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `volume` INTEGER")
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `vibrate` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `speakMemo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `ttsCachePath` TEXT")
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `ttsCacheKey` TEXT")
            }
        }

        /** v0.3 → v0.4：語音念一次後改播鈴聲 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `speakOnceThenRing` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v0.5 → v0.6：純震動鬧鐘、計時器群組 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `silent` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `timers` ADD COLUMN `groupId` INTEGER")
                db.execSQL("ALTER TABLE `timers` ADD COLUMN `pausedByGroup` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timer_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`expanded` INTEGER NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /** v0.6 → v0.7：群組底色 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `timer_groups` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT -1")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "alarm.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
