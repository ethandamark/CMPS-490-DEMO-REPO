package com.CMPS490.weathertracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WeatherCacheEntity::class,
        OfflineWeatherSnapshotEntity::class,
        HourlyPredictionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class WeatherDatabase : RoomDatabase() {

    abstract fun weatherCacheDao(): WeatherCacheDao
    abstract fun offlineWeatherSnapshotDao(): OfflineWeatherSnapshotDao
    abstract fun hourlyPredictionDao(): HourlyPredictionDao

    companion object {
        private const val DB_NAME = "weather_tracker.db"

        @Volatile
        private var instance: WeatherDatabase? = null

        fun getInstance(context: Context): WeatherDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WeatherDatabase::class.java,
                    DB_NAME,
                )
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
    }
}
