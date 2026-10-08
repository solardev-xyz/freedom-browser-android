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
    version = 8,
    exportSchema = true,
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
         * v3 -> v4: bookmarks get a `position` (#264), the order the user
         * arranges them in. Numbered from the order they were listed in
         * until now — newest first — so nobody's list reshuffles on
         * upgrade.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `bookmarks` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE `bookmarks` SET `position` = (" +
                        "SELECT COUNT(*) FROM `bookmarks` AS `b` " +
                        "WHERE `b`.`createdAt` > `bookmarks`.`createdAt` " +
                        "OR (`b`.`createdAt` = `bookmarks`.`createdAt` AND `b`.`id` > `bookmarks`.`id`))",
                )
            }
        }

        /**
         * v4 -> v5: pause and resume for downloads (#265) — the If-Range
         * validator, whether the server serves ranges, a note, and the
         * User-Agent the first request sent, which a resume or retry
         * sends again (#180). Additive; existing rows read as not
         * resumable, with no note and no recorded User-Agent.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `validator` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `resumable` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `note` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `userAgent` TEXT")
            }
        }

        /**
         * v5 -> v6: a download can be saved where the user picked (#322),
         * *Ask where to save each file*. Additive; existing rows read as
         * saved to Download/Freedom, which they were.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `saveTo` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `saveToCreated` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 -> v7 (#461): drop history and bookmark rows whose address is
         * longer than [BrowsingRepository.MAX_URL_CHARS], and cut titles
         * to [BrowsingRepository.MAX_TITLE_CHARS] — what the repository
         * refuses to write from now on. A row near 2 MB doesn't fit the
         * window Android reads results through, so before this, one such
         * visit made the Home page and History crash on every open; the
         * statements here never read it into one. A download's row keeps
         * no address that long either: its retry address is blanked and
         * its listed one cut (R1-M3). No schema change.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val url = BrowsingRepository.MAX_URL_CHARS
                val title = BrowsingRepository.MAX_TITLE_CHARS
                for (table in listOf("history", "bookmarks")) {
                    db.execSQL("DELETE FROM `$table` WHERE length(`url`) > $url")
                    db.execSQL("UPDATE `$table` SET `title` = substr(`title`, 1, $title) WHERE length(`title`) > $title")
                }
                // A download's row (#461 R1-M3): an address too long to
                // keep can't be retried (blank), and the listed one is cut
                // with `…`, as new rows are (`storedSourceUrl`,
                // `storedDisplayUrl`). The row itself stays: it's the file.
                db.execSQL("UPDATE `downloads` SET `sourceUrl` = '' WHERE length(`sourceUrl`) > $url")
                db.execSQL(
                    "UPDATE `downloads` SET `displayUrl` = substr(`displayUrl`, 1, ${url - 1}) || '…' " +
                        "WHERE length(`displayUrl`) > $url",
                )
            }
        }

        /**
         * v7 -> v8 (#473): index history's `url`, which the address bar's
         * suggestions group by on every keystroke, and keep only the newest
         * [BrowsingRepository.MAX_HISTORY_VISITS] visits — what the
         * repository trims to on each new visit from now on.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
                db.execSQL(
                    "DELETE FROM `history` WHERE `id` <= (SELECT `id` FROM `history` " +
                        "ORDER BY `id` DESC LIMIT 1 OFFSET ${BrowsingRepository.MAX_HISTORY_VISITS})",
                )
            }
        }

        internal val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "freedom.db",
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
