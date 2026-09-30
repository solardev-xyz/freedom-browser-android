package baby.freedom.mobile.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bookmarks' `position` column (#264): the v3 -> v4 migration, and the
 * add / edit / move writes on top of it.
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
    private fun createDatabase(name: String, version: Int): SupportSQLiteDatabase {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${AppDatabase::class.java.name}/$version.json")
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

        // Opening it with the app's migrations runs v3 -> v4, and Room
        // checks the migrated tables against v4's entities (columns,
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
            assertEquals(4, db.openHelper.readableDatabase.version)
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

    @Test
    fun addEditAndMove() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = BrowsingRepository(db)
        suspend fun order() = db.bookmarks().all().first().map { it.url }
        suspend fun awaitOrder(expected: List<String>) = withTimeout(5_000) {
            db.bookmarks().all().first { list -> list.map { it.url } == expected }
        }
        try {
            val a = repo.bookmark("https://a.example/", "A").await()!!
            val b = repo.bookmark("https://b.example/", "B").await()!!
            val c = repo.bookmark("https://c.example/", "C").await()!!
            // New ones go on top.
            assertEquals(listOf("https://c.example/", "https://b.example/", "https://a.example/"), order())
            // Bookmarking an address again keeps the one there is.
            assertEquals(b, repo.bookmark("https://b.example/", "B again").await())
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
            repo.moveBookmark(c, a)
            awaitOrder(listOf("https://b.example/", "vitalik.eth", "https://c.example/"))
            repo.moveBookmark(a, null)
            awaitOrder(listOf("vitalik.eth", "https://b.example/", "https://c.example/"))
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
}
