package baby.freedom.mobile.agent

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chat history's v1 -> v2 migration (#595): every chat from before
 * gets an approval mode, the strictest, and keeps its turns. Room checks
 * the migrated table against [ChatSession] (columns, defaults) on open.
 */
@RunWith(AndroidJUnit4::class)
class AgentDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A v1 database exactly as Room created it, from the exported schema. */
    private fun createV1(name: String): SupportSQLiteDatabase {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${AgentDatabase::class.java.name}/1.json")
            .bufferedReader().use { it.readText() }
        val schema = JSONObject(json).getJSONObject("database")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not reached")
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun chatsFromBeforeAskEveryTimeAndKeepTheirTurns() {
        val name = "agent-migration-1-2.db"
        context.deleteDatabase(name)
        createV1(name).apply {
            execSQL(
                "INSERT INTO agent_sessions (id, title, connectionId, modelId, createdAt, updatedAt, summary, summaryThrough) " +
                    "VALUES ('s1', 'Hello', 'c', 'm', 1, 2, NULL, 0)",
            )
            execSQL(
                "INSERT INTO agent_turns (id, sessionId, position, userText, assistantText, status, startedAt) " +
                    "VALUES ('t1', 's1', 0, 'hi', 'hello', 'completed', 1)",
            )
            close()
        }
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, name)
            .addMigrations(AgentDatabase.MIGRATION_1_2)
            .build()
        try {
            runBlocking {
                val session = db.chats().session("s1")!!
                assertEquals(ApprovalMode.EveryInteraction.wire, session.approvalMode)
                assertEquals(listOf("hello"), db.chats().turns("s1").map { it.assistantText })
                assertEquals(1, db.chats().setApprovalMode("s1", ApprovalMode.SensitiveActions.wire))
                assertEquals(ApprovalMode.SensitiveActions, ApprovalMode.of(db.chats().session("s1")!!.approvalMode))
            }
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
