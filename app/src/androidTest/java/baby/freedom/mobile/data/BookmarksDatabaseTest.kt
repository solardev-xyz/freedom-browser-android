package baby.freedom.mobile.data

import androidx.room.Room
import baby.freedom.mobile.browser.BookmarkAddress
import baby.freedom.mobile.browser.bookmarkAddress
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bookmarks' `position` column (#264): the v3 -> v4 migration, and the
 * add / edit / move writes on top of it. Also the v4 -> v5 downloads
 * columns (#265), from both a v3 and a v4 database.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A database file [name] exactly as Room created it at [version], from
     * that version's exported schema (`app/schemas`, handed to this APK as
     * assets): its tables, indices and Room's identity hash — what
     * room-testing's `MigrationTestHelper.createDatabase` does.
     */
    private fun createDatabase(
        name: String,
        version: Int,
        schemaVersion: Int = version,
        tables: Set<String>? = null,
    ): SupportSQLiteDatabase {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${AppDatabase::class.java.name}/$schemaVersion.json")
            .bufferedReader().use { it.readText() }
        val schema = JSONObject(json).getJSONObject("database")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        if (tables != null && table !in tables) continue
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            db.execSQL(
                                indices.getJSONObject(j).getString("createSql")
                                    .replace("\${TABLE_NAME}", table),
                            )
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("not reached")
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun migrationKeepsTheNewestFirstOrderAndEveryRow() {
        val name = "migration-3-4.db"
        context.deleteDatabase(name)
        createDatabase(name, 3).apply {
            // Inserted out of order; two share a createdAt (the higher id
            // was listed first).
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (1, 'https://a.example/', 'A', 100)")
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (2, 'vitalik.eth', 'V', 300)")
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (3, 'bzz://x.eth', 'X', 200)")
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (4, 'rad://z3', 'R', 200)")
            execSQL("INSERT INTO history (url, title, visitedAt) VALUES ('https://h.example/', 'H', 5)")
            close()
        }

        // Opening it with the app's migrations runs v3 -> v4 -> v5 -> v6, and
        // Room checks the migrated tables against the entities (columns,
        // types, defaults, indices) before anything reads them — a
        // mismatch throws here.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            db.openHelper.readableDatabase.query("SELECT id, position FROM bookmarks ORDER BY id").use { c ->
                val positions = buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
                assertEquals(mapOf(2L to 0L, 4L to 1L, 3L to 2L, 1L to 3L), positions)
            }
            assertEquals(6, db.openHelper.readableDatabase.version)
            runBlocking {
                val all = db.bookmarks().all().first()
                assertEquals(listOf(2L, 4L, 3L, 1L), all.map { it.id })
                assertEquals(listOf("vitalik.eth", "rad://z3", "bzz://x.eth", "https://a.example/"), all.map { it.url })
                assertEquals(listOf("V", "R", "X", "A"), all.map { it.title })
                assertEquals(1, db.history().recent().first().size)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A database a `main` build left at v4 (bookmarks with `position`, no
     * pause/resume columns): v4 -> v5 adds the downloads columns, keeps
     * every row, and old downloads read as not resumable.
     */
    @Test
    fun migrationFromFourAddsTheDownloadColumns() {
        val name = "migration-4-5.db"
        context.deleteDatabase(name)
        createDatabase(name, 4).apply {
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt, position) VALUES (1, 'vitalik.eth', 'V', 100, 0)")
            execSQL(
                "INSERT INTO downloads (id, fileName, displayUrl, sourceUrl, mimeType, contentUri, status, " +
                    "totalBytes, receivedBytes, error, startedAt, finishedAt, refererOrigin) VALUES " +
                    "(7, 'a.zip', 'https://d.example/a.zip', 'https://d.example/a.zip', 'application/zip', " +
                    "'content://x/1', 'COMPLETE', 10, 10, NULL, 50, 60, 'https://d.example/')",
            )
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals(6, db.openHelper.readableDatabase.version)
            runBlocking {
                val d = db.downloads().get(7)!!
                assertEquals("a.zip", d.fileName)
                assertEquals("https://d.example/", d.refererOrigin)
                assertNull(d.validator)
                assertFalse(d.resumable)
                assertNull(d.note)
                assertNull(d.userAgent)
                assertNull(d.saveTo)
                assertFalse(d.saveToCreated)
                assertEquals(listOf("vitalik.eth"), db.bookmarks().all().first().map { it.url })
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * v5 -> v6 (#322) adds where a download is saved; a paused download
     * keeps its resume state and reads as saved to Download/Freedom.
     */
    @Test
    fun migrationFromFiveAddsTheSaveTarget() {
        val name = "migration-5-6.db"
        context.deleteDatabase(name)
        createDatabase(name, 5).apply {
            execSQL(
                "INSERT INTO downloads (id, fileName, displayUrl, sourceUrl, mimeType, contentUri, status, " +
                    "totalBytes, receivedBytes, error, startedAt, finishedAt, refererOrigin, validator, resumable, " +
                    "note, userAgent) VALUES " +
                    "(9, 'b.iso', 'https://d.example/b.iso', 'https://d.example/b.iso', 'application/octet-stream', " +
                    "NULL, 'paused', 100, 40, NULL, 50, NULL, NULL, '\"e1\"', 1, NULL, 'UA')",
            )
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals(6, db.openHelper.readableDatabase.version)
            runBlocking {
                val d = db.downloads().get(9)!!
                assertEquals("paused", d.status)
                assertEquals(40L, d.receivedBytes)
                assertEquals("\"e1\"", d.validator)
                assertTrue(d.resumable)
                assertEquals("UA", d.userAgent)
                assertNull(d.saveTo)
                assertFalse(d.saveToCreated)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * v1 (history and bookmarks) and v2 (+ favicons) predate the exported
     * schemas, so they are built from v3's — their tables were the same
     * entities until v3 added `downloads` (v1 -> v2 -> v3 kept them
     * unchanged). A database from v0.6.5 or earlier takes these steps on
     * its way to v6: every row kept, bookmarks numbered newest first,
     * and Room's check of the migrated tables against the entities passes.
     */
    @Test
    fun migrationFromOneKeepsEveryRow() = migrateFromBeforeExportedSchemas(1, setOf("history", "bookmarks"))

    @Test
    fun migrationFromTwoKeepsEveryRow() = migrateFromBeforeExportedSchemas(2, setOf("history", "bookmarks", "favicons"))

    private fun migrateFromBeforeExportedSchemas(version: Int, tables: Set<String>) {
        val name = "migration-$version-6.db"
        context.deleteDatabase(name)
        createDatabase(name, version, schemaVersion = 3, tables = tables).apply {
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (1, 'https://a.example/', 'A', 100)")
            execSQL("INSERT INTO bookmarks (id, url, title, createdAt) VALUES (2, 'vitalik.eth', 'V', 300)")
            execSQL("INSERT INTO history (url, title, visitedAt) VALUES ('https://h.example/', 'H', 5)")
            if ("favicons" in tables) {
                execSQL("INSERT INTO favicons (origin, data, updatedAt) VALUES ('https://a.example', X'89504E47', 7)")
            }
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals(6, db.openHelper.readableDatabase.version)
            runBlocking {
                assertEquals(listOf("vitalik.eth", "https://a.example/"), db.bookmarks().all().first().map { it.url })
                assertEquals(listOf(0L, 1L), db.bookmarks().all().first().map { it.position })
                assertEquals(listOf("https://h.example/"), db.history().recent().first().map { it.url })
                val icon = db.favicons().get("https://a.example").first()
                if ("favicons" in tables) assertEquals(4, icon?.size) else assertNull(icon)
                assertTrue(db.downloads().all().first().isEmpty())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun addEditAndMove() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = BrowsingRepository(db)
        suspend fun order() = db.bookmarks().all().first().map { it.url }
        suspend fun awaitOrder(expected: List<String>) = withTimeout(5_000) {
            db.bookmarks().all().first { list -> list.map { it.url } == expected }
        }
        try {
            val a = repo.bookmark("https://a.example/", "A").await()!!.id
            val b = repo.bookmark("https://b.example/", "B").await()!!.id
            val c = repo.bookmark("https://c.example/", "C").await()!!.id
            // New ones go on top.
            assertEquals(listOf("https://c.example/", "https://b.example/", "https://a.example/"), order())
            // Bookmarking an address again keeps the one there is.
            assertEquals(Bookmarked(b, added = false), repo.bookmark("https://b.example/", "B again").await())
            assertEquals("B", db.bookmarks().byId(b)!!.title)
            // Not a page bookmarks keep.
            assertNull(repo.bookmark("javascript:alert(1)", "x").await())

            // Rename and re-address.
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(a, "Name", "vitalik.eth").await())
            assertEquals("Name", db.bookmarks().byId(a)!!.title)
            assertEquals("vitalik.eth", db.bookmarks().byId(a)!!.url)
            // Another bookmark's address is refused, and nothing changes.
            val dup = repo.editBookmark(c, "C", "vitalik.eth").await()
            assertTrue(dup is BookmarkEditResult.Duplicate)
            assertEquals("Name", (dup as BookmarkEditResult.Duplicate).title)
            assertEquals("https://c.example/", db.bookmarks().byId(c)!!.url)
            // Its own address is fine.
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(c, "C2", "https://c.example/").await())

            // Move c to the bottom, then a to the top.
            assertTrue(repo.moveBookmark(c, a).await())
            awaitOrder(listOf("https://b.example/", "vitalik.eth", "https://c.example/"))
            assertTrue(repo.moveBookmark(a, null).await())
            awaitOrder(listOf("vitalik.eth", "https://b.example/", "https://c.example/"))
            // A move that goes nowhere writes nothing, and says so (#296 R1-M2).
            assertFalse(repo.moveBookmark(a, null).await())
            assertFalse(repo.moveBookmark(999, null).await())
            // A new one still lands on top of a hand-made order.
            repo.bookmark("https://d.example/", "D").await()
            assertEquals(
                listOf("https://d.example/", "vitalik.eth", "https://b.example/", "https://c.example/"),
                order(),
            )

            // A removed bookmark can't be edited.
            repo.unbookmark("https://b.example/")
            awaitOrder(listOf("https://d.example/", "vitalik.eth", "https://c.example/"))
            assertEquals(BookmarkEditResult.Gone, repo.editBookmark(b, "B", "https://b.example/").await())
        } finally {
            db.close()
        }
    }

    /**
     * #296 R1-F1: a bookmark whose address was edited to another spelling
     * of the page (`localhost:8730` for the page's `http://localhost:8730/`,
     * `ens://x.eth` for `ipfs://x.eth/`) is still that page's bookmark —
     * the star is filled, re-adding finds it, Remove removes it, and the
     * edit dialog's duplicate check sees it.
     */
    @Test
    fun anotherSpellingOfTheSamePageIsTheSameBookmark() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = BrowsingRepository(db)
        suspend fun starred(url: String) = withTimeout(5_000) { repo.isBookmarked(url).first() }
        try {
            val page = "http://localhost:8730/"
            val a = repo.bookmark(page, "Local").await()!!.id
            val typed = (bookmarkAddress("localhost:8730") as BookmarkAddress.Ok).url
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(a, "Local", typed).await())
            assertEquals(page, db.bookmarks().byId(a)!!.url)
            assertTrue(starred(page))
            // Even a row saved in another spelling before this fix.
            db.bookmarks().update(a, "http://LOCALHOST:8730", "Local")
            assertTrue(starred(page))
            assertEquals(Bookmarked(a, added = false), repo.bookmark(page, "Again").await())
            assertEquals(1, db.bookmarks().all().first().size)

            val n = repo.bookmark("ipfs://x.eth/", "X").await()!!.id
            assertTrue(starred("x.eth"))
            assertTrue(starred("bzz://x.eth"))
            val dup = repo.editBookmark(a, "Local", (bookmarkAddress("ens://x.eth") as BookmarkAddress.Ok).url).await()
            assertTrue(dup is BookmarkEditResult.Duplicate)
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(n, "X", "x.eth").await())

            repo.unbookmark(page)
            withTimeout(5_000) { db.bookmarks().all().first { list -> list.none { it.id == a } } }
            assertFalse(starred(page))
            assertTrue(starred("ipfs://x.eth/"))
        } finally {
            db.close()
        }
    }

    /**
     * #296 R2-F1: two rows saved by older, exact-match code for one page
     * (`vitalik.eth` and `ipfs://vitalik.eth/`) show as two bookmarks, so
     * the list's Remove takes out only the row it was on; the star's
     * Remove (by the page) still takes out both.
     */
    @Test
    fun theListRemovesOneRowNotEverySpelling(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = BrowsingRepository(db)
        try {
            val first = db.bookmarks().upsert(BookmarkEntry(url = "vitalik.eth", title = "Vitalik", createdAt = 1))
            val second = db.bookmarks().upsert(
                BookmarkEntry(url = "ipfs://vitalik.eth/", title = "Vitalik – blog", createdAt = 2),
            )
            // Either row can still be renamed, its address untouched
            // (#296 R6-F1) — the other one is on the same page already.
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(first, "Vitalik Home", "vitalik.eth").await())
            assertEquals("Vitalik Home", db.bookmarks().byId(first)!!.title)
            assertEquals(
                BookmarkEditResult.Saved,
                repo.editBookmark(second, "Vitalik blog", "ipfs://vitalik.eth/").await(),
            )
            assertEquals("ipfs://vitalik.eth/", db.bookmarks().byId(second)!!.url)
            // Or re-spelled onto that same page.
            assertEquals(BookmarkEditResult.Saved, repo.editBookmark(first, "Vitalik Home", (bookmarkAddress("ens://vitalik.eth") as BookmarkAddress.Ok).url).await())
            repo.editBookmark(first, "Vitalik Home", "vitalik.eth").await()
            // Moving onto a different bookmarked page is still refused.
            val other = db.bookmarks().upsert(BookmarkEntry(url = "https://a.example/", title = "A", createdAt = 4))
            assertTrue(repo.editBookmark(other, "A", "vitalik.eth").await() is BookmarkEditResult.Duplicate)
            db.bookmarks().delete(other)

            repo.deleteBookmark(first)
            val left = withTimeout(5_000) {
                db.bookmarks().all().first { list -> list.none { it.id == first } && list.size == 1 }
            }
            assertEquals(listOf(second), left.map { it.id })
            assertEquals("Vitalik blog", left.single().title)

            db.bookmarks().upsert(BookmarkEntry(url = "vitalik.eth", title = "Vitalik", createdAt = 3))
            repo.unbookmark("vitalik.eth")
            withTimeout(5_000) { db.bookmarks().all().first { it.isEmpty() } }
        } finally {
            db.close()
        }
    }
}
