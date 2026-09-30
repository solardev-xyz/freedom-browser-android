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
    version = 5,
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

        /**
         * v3 -> v4: pause and resume for downloads (#265) — the If-Range
         * validator, whether the server serves ranges, and a note.
         * Additive; existing rows read as not resumable.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `validator` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `resumable` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `note` TEXT")
            }
        }

        /**
         * v4 -> v5: the User-Agent a download's first request sent, which
         * a resume or retry sends again (#265, #180). Null on older rows.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `userAgent` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "freedom.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
