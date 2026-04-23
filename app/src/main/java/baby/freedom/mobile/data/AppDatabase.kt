package baby.freedom.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntry::class, BookmarkEntry::class, FaviconEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun favicons(): FaviconDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 -> v2: add the `favicons` table. Purely additive — the
         * existing history/bookmarks tables are untouched, so users
         * upgrading don't lose any data.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favicons` (" +
                        "`origin` TEXT NOT NULL, " +
                        "`data` BLOB NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`origin`))",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "freedom.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
