package com.shuaib.classmate.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NoticeEntity::class, TimetableEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ClassMateDatabase : RoomDatabase() {
    abstract fun noticeDao(): NoticeDao
    abstract fun timetableDao(): TimetableDao

    companion object {
        @Volatile private var instance: ClassMateDatabase? = null

        fun getInstance(context: Context): ClassMateDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClassMateDatabase::class.java,
                    "classmate-offline.db"
                ).build().also { instance = it }
            }
        }
    }
}
