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
 * The chat history's migrations: v1 -> v2 (#595), every chat from before
 * gets an approval mode, the strictest, and keeps its turns; v2 -> v3
 * (#601), every turn from before carries no files. Room checks the
 * migrated tables against [ChatSession] and [ChatTurn] (columns,
 * defaults) on open.
 */
@RunWith(AndroidJUnit4::class)
class AgentDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A database at [version] exactly as Room created it, from the exported schema. */
    private fun create(name: String, version: Int): SupportSQLiteDatabase {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${AgentDatabase::class.java.name}/$version.json")
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
        create(name, 1).apply {
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
            .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3)
            .build()
        try {
            runBlocking {
                val session = db.chats().session("s1")!!
                assertEquals(ApprovalMode.EveryInteraction.wire, session.approvalMode)
                assertEquals(listOf("hello"), db.chats().turns("s1").map { it.assistantText })
                assertEquals(1, db.chats().setApprovalMode("s1", ApprovalMode.SensitiveActions.wire))
                assertEquals(ApprovalMode.SensitiveActions, ApprovalMode.of(db.chats().session("s1")!!.approvalMode))
            }
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** v2 -> v3 (#601): every turn from before carries no files, and a new one can. */
    @Test
    fun turnsFromBeforeCarryNoAttachments() {
        val name = "agent-migration-2-3.db"
        context.deleteDatabase(name)
        create(name, 2).apply {
            execSQL(
                "INSERT INTO agent_sessions (id, title, connectionId, modelId, createdAt, updatedAt, summary, summaryThrough, approvalMode) " +
                    "VALUES ('s1', 'Hello', 'c', 'm', 1, 2, NULL, 0, 'every_interaction')",
            )
            execSQL(
                "INSERT INTO agent_turns (id, sessionId, position, userText, assistantText, status, startedAt) " +
                    "VALUES ('t1', 's1', 0, 'hi', 'hello', 'completed', 1)",
            )
            close()
        }
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, name)
            .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3)
            .build()
        try {
            runBlocking {
                val old = db.chats().turns("s1").single()
                assertEquals("hello", old.assistantText)
                assertEquals(null, old.attachments)
                val files = """[{"resourceId":"attachment_0123456789abcdef0123","kind":"file","name":"a.pdf","bytes":4,"mimeType":"application/pdf","category":"pdf"}]"""
                db.chats().insertTurn(
                    ChatTurn("t2", "s1", 1, "this?", status = ChatStatus.COMPLETED, startedAt = 3, attachments = files),
                )
                assertEquals(listOf("a.pdf"), Attachment.parseList(db.chats().turns("s1").last().attachments).map { it.name })
                assertEquals(listOf("s1"), db.chats().sessionIds())
            }
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
