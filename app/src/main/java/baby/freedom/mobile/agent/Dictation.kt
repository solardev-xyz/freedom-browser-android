package baby.freedom.mobile.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

/*
 * Voice dictation in the agent's composer (#578). Desktop transcribes
 * with a cloud provider or whisper.cpp; on Android the first version
 * uses the system's own SpeechRecognizer (#550), preferring the
 * on-device one (API 31+) and saying where the audio goes either way.
 * Dictated text lands in the composer and is never sent by itself.
 */

/** Which of the phone's recognizers dictation uses. */
internal enum class RecognizerKind {
    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer`: recognized on this phone. */
    OnDevice,

    /** `SpeechRecognizer.createSpeechRecognizer`: the phone's speech service, which may send audio to its provider. */
    Service,
}

internal object DictationRecognizers {
    /**
     * The recognizers this phone offers: the on-device one (API 31+) when
     * Android reports it, and the speech service when there is one.
     */
    fun available(sdk: Int, onDeviceAvailable: () -> Boolean, serviceAvailable: () -> Boolean): Set<RecognizerKind> = buildSet {
        if (sdk >= Build.VERSION_CODES.S && onDeviceAvailable()) add(RecognizerKind.OnDevice)
        if (serviceAvailable()) add(RecognizerKind.Service)
    }

    fun available(context: Context): Set<RecognizerKind> = available(
        Build.VERSION.SDK_INT,
        onDeviceAvailable = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && safely { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
        },
        serviceAvailable = { safely { SpeechRecognizer.isRecognitionAvailable(context) } },
    )

    /**
     * The recognizer dictation uses out of [available]: the speech
     * service when the user picked it in Settings ([preferService]) and
     * the phone has one, else the on-device one when the phone has it,
     * else the service, else none (null: no microphone button). Android
     * can report an on-device recognizer that then never works, so the
     * service is the user's to choose; it's never switched to by itself.
     * Every place that names the destination (the button, the listening
     * line, Settings) and the one that creates the recognizer read this
     * same answer, so the label can't say "on this phone" while audio
     * goes to a service.
     */
    fun pick(available: Set<RecognizerKind>, preferService: Boolean): RecognizerKind? = when {
        preferService && RecognizerKind.Service in available -> RecognizerKind.Service
        RecognizerKind.OnDevice in available -> RecognizerKind.OnDevice
        RecognizerKind.Service in available -> RecognizerKind.Service
        else -> null
    }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "checking for a speech recognizer failed (${e.javaClass.simpleName})")
        false
    }

    private const val TAG = "Dictation"
}

/**
 * The languages Settings → AI → Voice dictation offers besides the app
 * language: desktop's list.
 */
internal val DICTATION_LANGUAGES = listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "ja", "zh", "ko", "uk")

/**
 * The language to recognize, as a BCP 47 tag: the one picked in
 * Settings when it's still one offered, else the app's (the per-app or
 * phone language, [appLocale]), whatever language the app's text
 * happens to be in.
 */
internal fun dictationLanguage(override: String?, appLocale: Locale): String =
    override?.takeIf { it in DICTATION_LANGUAGES }
        // Without -u-… extensions (a regional preference's), which a recognizer may refuse.
        ?: appLocale.stripExtensions().toLanguageTag().takeIf { it != "und" }
        ?: "en"

/** [base] with [spoken] after it, a space between when neither side has one. */
internal fun joinDictated(base: String, spoken: String): String {
    val words = spoken.trim()
    if (words.isEmpty()) return base
    if (base.isEmpty() || base.last().isWhitespace()) return base + words
    return "$base $words"
}

/** Why dictation stopped without finishing. */
internal enum class DictationFailure {
    /** Nothing was heard: the draft is as it was. */
    NoSpeech,

    /** Freedom's microphone permission is refused (here or in Android settings). */
    Permission,

    /**
     * The recognizer itself may not use the microphone: Freedom's own
     * permission was granted before the run started, so this is the
     * speech app's.
     */
    RecognizerPermission,

    /** The recognizer doesn't support the language. */
    LanguageUnsupported,

    /** The recognizer supports the language but its model isn't on the phone (yet). */
    LanguageUnavailable,

    /** The speech service couldn't be reached (or, for the on-device recognizer, didn't answer). */
    Network,

    /** Another app is using the recognizer. */
    Busy,

    /** The microphone couldn't be read. */
    Audio,

    /** Anything else. */
    Other,
    ;

    companion object {
        fun of(error: Int): DictationFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NoSpeech
            // A run only starts once Freedom holds RECORD_AUDIO, so this is the recognizer's own.
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognizerPermission
            ERROR_LANGUAGE_NOT_SUPPORTED -> LanguageUnsupported
            ERROR_LANGUAGE_UNAVAILABLE -> LanguageUnavailable
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> Network
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Busy
            SpeechRecognizer.ERROR_AUDIO -> Audio
            else -> Other
        }

        // API 31 constants, by value so this reads the same on API 30.
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/** Where the composer's dictation is. */
internal sealed interface DictationStatus {
    data object Idle : DictationStatus

    /** The last run ended with text in the draft (said once to TalkBack). */
    data object Done : DictationStatus

    /**
     * Listening with [kind]; [hearing] once the recognizer is ready for
     * speech; [finishing] once asked to stop, while the recognizer works
     * out what was heard (up to [Dictation.FINISH_DEADLINE_MS]).
     */
    data class Listening(val kind: RecognizerKind, val hearing: Boolean, val finishing: Boolean = false) : DictationStatus

    /** The last run stopped on [failure]; the draft keeps whatever it showed. */
    data class Failed(val failure: DictationFailure, val kind: RecognizerKind) : DictationStatus
}

/** One recognizer, for one listening run at a time. Calls back on the main thread. */
internal interface Recognizer {
    interface Events {
        fun ready()
        fun partial(text: String)
        fun final(text: String)
        fun error(code: Int)
    }

    fun start(language: String, events: Events)

    /** Stop listening and recognize what was heard (a [Events.final] or [Events.error] follows). */
    fun stop()

    /** Stop at once; nothing more is called back. */
    fun cancel()

    fun destroy()
}

/**
 * The composer's dictation (#578): starts a [Recognizer] of [kind] and
 * writes what it hears into the draft through [setDraft], after the
 * text the draft had when dictation began. Partial results show as they
 * come; the final one replaces them. Nothing here sends.
 *
 * Each run is numbered, and a callback from any run but the current one
 * is dropped: a result arriving after [cancel] (the user typed, switched
 * chats, left the page) can't land in a draft that isn't its own.
 */
internal class Dictation(
    val kind: RecognizerKind,
    private val create: (RecognizerKind) -> Recognizer?,
    private val setDraft: (String) -> Unit,
    /** Runs a block later on the main thread: the deadline for a run asked to finish. */
    private val after: (Long, () -> Unit) -> Unit = { ms, block -> Handler(Looper.getMainLooper()).postDelayed(block, ms) },
) {
    private val _status = MutableStateFlow<DictationStatus>(DictationStatus.Idle)
    val status: StateFlow<DictationStatus> = _status.asStateFlow()

    private var recognizer: Recognizer? = null
    private var run = 0
    private var base = ""
    private var heard = ""
    private var finishing = false

    /** The draft as dictation last wrote it: any other value came from the user. */
    var written: String? = null
        private set

    val listening: Boolean get() = _status.value is DictationStatus.Listening

    /** Starts listening, adding to [draft]. */
    fun start(draft: String, language: String) {
        cancel()
        val r = recognizer ?: try {
            create(kind)
        } catch (e: RuntimeException) {
            Log.w(TAG, "creating the speech recognizer failed (${e.javaClass.simpleName})")
            null
        }.also { recognizer = it }
        if (r == null) {
            _status.value = DictationStatus.Failed(DictationFailure.Other, kind)
            return
        }
        val mine = ++run
        base = draft
        heard = ""
        finishing = false
        written = draft
        _status.value = DictationStatus.Listening(kind, hearing = false)
        val events = object : Recognizer.Events {
            override fun ready() {
                if (mine == run && listening) _status.value = DictationStatus.Listening(kind, hearing = true, finishing = finishing)
            }

            override fun partial(text: String) {
                if (mine == run && listening) write(text)
            }

            override fun final(text: String) {
                if (mine != run || !listening) return
                // An empty final after partials keeps what was shown.
                if (text.isNotBlank()) write(text)
                end(if (heard.isNotBlank()) DictationStatus.Done else DictationStatus.Idle)
            }

            override fun error(code: Int) {
                if (mine != run || !listening) return
                Log.i(TAG, "speech recognizer error $code")
                val failure = DictationFailure.of(code)
                end(
                    when {
                        // Text shown already stays; nothing more to hear isn't a failure then.
                        failure == DictationFailure.NoSpeech && heard.isNotBlank() -> DictationStatus.Done
                        // The recognizer's answer to our own stop, not a failure.
                        finishing && code == SpeechRecognizer.ERROR_CLIENT ->
                            if (heard.isNotBlank()) DictationStatus.Done else DictationStatus.Idle
                        else -> DictationStatus.Failed(failure, kind)
                    },
                )
            }
        }
        try {
            r.start(language, events)
        } catch (e: RuntimeException) {
            Log.w(TAG, "starting the speech recognizer failed (${e.javaClass.simpleName})")
            end(DictationStatus.Failed(DictationFailure.Other, kind))
        }
    }

    /** Stops listening and keeps what's recognized from what was heard so far. */
    fun finish() {
        // A second tap while finishing changes nothing: the first one's deadline stands.
        if (!listening || finishing) return
        val mine = run
        finishing = true
        (_status.value as? DictationStatus.Listening)?.let { _status.value = it.copy(finishing = true) }
        try {
            recognizer?.stop()
        } catch (e: RuntimeException) {
            end(if (heard.isNotBlank()) DictationStatus.Done else DictationStatus.Idle)
            return
        }
        // A recognizer that never answers the stop doesn't keep the
        // microphone, or the composer listening, for good.
        after(FINISH_DEADLINE_MS) {
            if (mine == run && listening) end(if (heard.isNotBlank()) DictationStatus.Done else DictationStatus.Idle)
        }
    }

    /**
     * Stops at once and drops anything still to come; the draft keeps
     * what it shows. For the user's own edit, Send, a chat switch, an
     * approval, the app going to the background.
     */
    fun cancel() {
        if (!listening) return
        end(DictationStatus.Idle)
    }

    /**
     * The user changed the draft to [value]: dictation stops rather than
     * write over their edit, and a failure's line goes.
     */
    fun edited(value: String) {
        if (listening && value != written) cancel()
        dismiss()
    }

    /** The microphone permission was refused when asked for. */
    fun refused() {
        cancel()
        _status.value = DictationStatus.Failed(DictationFailure.Permission, kind)
    }

    /**
     * Freedom now holds the microphone permission (checked on resume, after
     * a trip to Android settings): a refusal's line no longer applies.
     */
    fun permissionGranted() {
        if ((_status.value as? DictationStatus.Failed)?.failure == DictationFailure.Permission) _status.value = DictationStatus.Idle
    }

    /** Hides a failure's (or a finished run's) line. */
    fun dismiss() {
        val now = _status.value
        if (now is DictationStatus.Failed || now == DictationStatus.Done) _status.value = DictationStatus.Idle
    }

    fun destroy() {
        cancel()
        try {
            recognizer?.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "releasing the speech recognizer failed (${e.javaClass.simpleName})")
        }
        recognizer = null
    }

    private fun write(text: String) {
        heard = text
        val next = joinDictated(base, text)
        written = next
        setDraft(next)
    }

    private fun end(next: DictationStatus) {
        run++
        written = null
        // The microphone is let go of now, not when the recognizer gets
        // round to it; the recognizer stays usable for the next run.
        try {
            recognizer?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopping the speech recognizer failed (${e.javaClass.simpleName})")
        }
        _status.value = next
    }

    companion object {
        private const val TAG = "Dictation"

        /** How long a run asked to finish waits for the recognizer's answer. */
        const val FINISH_DEADLINE_MS = 5_000L
    }
}

/** [Recognizer] over Android's [SpeechRecognizer]. Main thread only. */
internal class AndroidRecognizer(context: Context, kind: RecognizerKind) : Recognizer {
    private val speech: SpeechRecognizer = when (kind) {
        RecognizerKind.OnDevice -> {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        RecognizerKind.Service -> SpeechRecognizer.createSpeechRecognizer(context)
    }
    private val onDevice = kind == RecognizerKind.OnDevice

    override fun start(language: String, events: Recognizer.Events) {
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = events.ready()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = events.error(error)
            override fun onResults(results: Bundle?) = events.final(best(results))
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults).takeIf { it.isNotBlank() }?.let(events::partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .apply { if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) },
        )
    }

    override fun stop() = speech.stopListening()

    override fun cancel() = speech.cancel()

    override fun destroy() = speech.destroy()

    private fun best(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

/**
 * Settings → AI → Voice dictation's picks (#578): the language (null
 * follows the app language) and whether to use the phone's speech
 * service over the on-device recognizer. Its own small file, apart from
 * the AI settings, so a pick never rewrites the keys' blob. A file that
 * can't be read reads as the app language and the on-device recognizer.
 */
internal class DictationSettings private constructor(private val context: Context) {
    val language: Flow<String?> = context.dictationSettings.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading dictation settings failed (${e.javaClass.simpleName})")
            emit(emptyPreferences())
        }
        .map { it[LANGUAGE]?.takeIf { tag -> tag in DICTATION_LANGUAGES } }

    /** Whether the user picked the phone's speech service over the on-device recognizer. */
    val preferService: Flow<Boolean> = context.dictationSettings.data
        .catch { e ->
            if (e !is IOException) throw e
            emit(emptyPreferences())
        }
        .map { it[PREFER_SERVICE] == true }

    /** Sets [preferService]; false when the write failed. */
    suspend fun setPreferService(prefer: Boolean): Boolean = try {
        context.dictationSettings.edit { if (prefer) it[PREFER_SERVICE] = true else it.remove(PREFER_SERVICE) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "saving the dictation recognizer failed (${e.javaClass.simpleName})")
        false
    }

    /** Sets the language ([tag] null: the app's); false when the write failed. */
    suspend fun setLanguage(tag: String?): Boolean = try {
        context.dictationSettings.edit { if (tag == null) it.remove(LANGUAGE) else it[LANGUAGE] = tag }
        true
    } catch (e: IOException) {
        Log.w(TAG, "saving the dictation language failed (${e.javaClass.simpleName})")
        false
    }

    companion object {
        private const val TAG = "DictationSettings"
        private val LANGUAGE = stringPreferencesKey("language")
        private val PREFER_SERVICE = booleanPreferencesKey("prefer_service")
        const val FILE_NAME = "freedom_agent_dictation"

        private val Context.dictationSettings by preferencesDataStore(
            name = FILE_NAME,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: DictationSettings? = null

        fun get(context: Context): DictationSettings = instance ?: synchronized(this) {
            instance ?: DictationSettings(context.applicationContext).also { instance = it }
        }
    }
}
