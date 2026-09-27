package baby.freedom.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HistoryEntry::class,
        BookmarkEntry::class,
        FaviconEntry::class,
        DownloadEntry::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun favicons(): FaviconDao
    abstract fun downloads(): DownloadDao

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

        /**
         * v2 -> v3: add the `downloads` table (download history, #79).
         * Additive like v1 -> v2.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`displayUrl` TEXT NOT NULL, " +
                        "`sourceUrl` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`contentUri` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "`totalBytes` INTEGER NOT NULL, " +
                        "`receivedBytes` INTEGER NOT NULL, " +
                        "`error` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`finishedAt` INTEGER, " +
                        "`refererOrigin` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloads_startedAt` " +
                        "ON `downloads` (`startedAt`)",
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
