package baby.freedom.mobile.agent

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * Chat history (#574), desktop's `session-history-store.js` in Room: a
 * session per chat and a turn per message the user sent, with the reply
 * the model gave. Its own database file (`freedom_agent.db`), apart from
 * browsing history, and kept out of backup and device transfer like the
 * AI settings (`res/xml/data_extraction_rules.xml`): a chat can quote
 * whatever was pasted into it. Private tabs never feed it (the chat isn't
 * offered from one).
 */

/** A turn's or a session's state, desktop's statuses. */
internal object ChatStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** It was running when the app stopped. */
    const val INTERRUPTED = "interrupted"
}

@Entity(tableName = "agent_sessions", indices = [Index("updatedAt")])
internal data class ChatSession(
    @PrimaryKey val id: String,
    val title: String,
    /** The model it last used. */
    val connectionId: String?,
    val modelId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The conversation before turn [summaryThrough], compacted (#574); null until it first is. */
    val summary: String? = null,
    val summaryThrough: Int = 0,
    /** Its [ApprovalMode] (#595), as stored; read through [ApprovalMode.of], so anything unknown asks every time. */
    @ColumnInfo(defaultValue = "every_interaction")
    val approvalMode: String = ApprovalMode.DEFAULT.wire,
)

@Entity(
    tableName = "agent_turns",
    foreignKeys = [
        ForeignKey(entity = ChatSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sessionId", "position"], unique = true)],
)
internal data class ChatTurn(
    @PrimaryKey val id: String,
    val sessionId: String,
    val position: Int,
    val userText: String,
    val assistantText: String = "",
    val status: String,
    val startedAt: Long,
    val durationMs: Long? = null,
    /** A [ChatError]'s name, when it didn't end in a whole answer. */
    val error: String? = null,
    /**
     * The files sent with the message (#601), as JSON ([Attachment.listJson]);
     * null for none. Shown under it, and named to the model again in later turns.
     */
    val attachments: String? = null,
)

@Dao
internal interface ChatDao {
    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun sessions(limit: Int = MAX_LISTED): Flow<List<ChatSession>>

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun session(id: String): ChatSession?

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    fun sessionFlow(id: String): Flow<ChatSession?>

    @Query("SELECT * FROM agent_turns WHERE sessionId = :sessionId ORDER BY position ASC")
    fun turnsFlow(sessionId: String): Flow<List<ChatTurn>>

    @Query("SELECT * FROM agent_turns WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun turns(sessionId: String): List<ChatTurn>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM agent_turns WHERE sessionId = :sessionId")
    suspend fun nextPosition(sessionId: String): Int

    @Insert
    suspend fun insertSession(session: ChatSession)

    @Insert
    suspend fun insertTurn(turn: ChatTurn)

    @Query("UPDATE agent_sessions SET connectionId = :connectionId, modelId = :modelId, updatedAt = :at WHERE id = :id")
    suspend fun touchSession(id: String, connectionId: String, modelId: String, at: Long)

    @Query("UPDATE agent_sessions SET approvalMode = :mode WHERE id = :id")
    suspend fun setApprovalMode(id: String, mode: String): Int

    @Query("UPDATE agent_sessions SET summary = :summary, summaryThrough = :through WHERE id = :id")
    suspend fun setSummary(id: String, summary: String, through: Int)

    @Query(
        "UPDATE agent_turns SET assistantText = :text, status = :status, durationMs = :durationMs, error = :error " +
            "WHERE id = :id",
    )
    suspend fun finishTurn(id: String, text: String, status: String, durationMs: Long?, error: String?)

    @Query("UPDATE agent_turns SET assistantText = :text WHERE id = :id AND status = 'running'")
    suspend fun saveProgress(id: String, text: String)

    /** Turns left running by an app that stopped: desktop's `markStaleRunningAsInterrupted`. */
    @Query("UPDATE agent_turns SET status = 'interrupted' WHERE status = 'running'")
    suspend fun markRunningInterrupted(): Int

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAll()

    /** Every chat's id, for sweeping attachments whose chat is gone (#601). */
    @Query("SELECT id FROM agent_sessions")
    suspend fun sessionIds(): List<String>

    /** A new session and its first turn, together. */
    @Transaction
    suspend fun start(session: ChatSession, turn: ChatTurn) {
        insertSession(session)
        insertTurn(turn)
    }

    companion object {
        const val MAX_LISTED = 500
    }
}

@Database(entities = [ChatSession::class, ChatTurn::class], version = 3, exportSchema = true)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        const val FILE_NAME = "freedom_agent.db"

        /** A chat's approval mode (#595); every chat from before asks every time, as it did. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_sessions ADD COLUMN approvalMode TEXT NOT NULL DEFAULT 'every_interaction'")
            }
        }

        /** The files a message was sent with (#601); every turn from before had none. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_turns ADD COLUMN attachments TEXT")
            }
        }

        @Volatile private var instance: AgentDatabase? = null

        fun get(context: Context): AgentDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
