package baby.freedom.mobile.agent

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The composer's dictation (#578): which recognizer, which language, and what lands in the draft. */
class DictationTest {
    @Test
    fun `the on-device recognizer when the phone has it on API 31 and up`() {
        assertEquals(RecognizerKind.OnDevice, DictationRecognizers.pick(31, { true }, { true }))
        assertEquals(RecognizerKind.OnDevice, DictationRecognizers.pick(36, { true }, { false }))
    }

    @Test
    fun `the speech service when there's no on-device recognizer`() {
        assertEquals(RecognizerKind.Service, DictationRecognizers.pick(31, { false }, { true }))
    }

    @Test
    fun `below API 31 the on-device recognizer isn't even asked about`() {
        var asked = false
        assertEquals(RecognizerKind.Service, DictationRecognizers.pick(30, { asked = true; true }, { true }))
        assertFalse(asked)
    }

    @Test
    fun `no recognizer at all means no dictation`() {
        assertNull(DictationRecognizers.pick(36, { false }, { false }))
        assertNull(DictationRecognizers.pick(30, { true }, { false }))
    }

    @Test
    fun `the language follows the app's unless one is picked`() {
        assertEquals("de-AT", dictationLanguage(null, Locale.forLanguageTag("de-AT")))
        assertEquals("fr", dictationLanguage("fr", Locale.forLanguageTag("de-AT")))
        // A regional preference's extension isn't passed on.
        assertEquals("en-US", dictationLanguage(null, Locale.forLanguageTag("en-US-u-mu-celsius")))
    }

    @Test
    fun `a stored language that isn't offered falls back to the app's`() {
        assertEquals("en-GB", dictationLanguage("xx-evil", Locale.forLanguageTag("en-GB")))
        assertEquals("en", dictationLanguage(null, Locale.ROOT))
    }

    @Test
    fun `dictated words follow the draft with one space`() {
        assertEquals("hello", joinDictated("", " hello "))
        assertEquals("Look up the weather", joinDictated("Look up", "the weather"))
        assertEquals("Look up the weather", joinDictated("Look up ", "the weather"))
        assertEquals("Look up\nthe weather", joinDictated("Look up\n", "the weather"))
        assertEquals("Look up", joinDictated("Look up", "  "))
    }

    @Test
    fun `recognizer errors map to what the composer says`() {
        assertEquals(DictationFailure.NoSpeech, DictationFailure.of(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(DictationFailure.NoSpeech, DictationFailure.of(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(DictationFailure.Permission, DictationFailure.of(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(DictationFailure.Language, DictationFailure.of(12))
        assertEquals(DictationFailure.Language, DictationFailure.of(13))
        assertEquals(DictationFailure.Network, DictationFailure.of(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(DictationFailure.Busy, DictationFailure.of(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(DictationFailure.Other, DictationFailure.of(SpeechRecognizer.ERROR_CLIENT))
    }

    private class FakeRecognizer : Recognizer {
        val events = mutableListOf<Recognizer.Events>()
        val languages = mutableListOf<String>()
        var stops = 0
        var cancels = 0
        var destroyed = false
        val last get() = events.last()

        override fun start(language: String, events: Recognizer.Events) {
            languages += language
            this.events += events
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }

        override fun destroy() {
            destroyed = true
        }
    }

    /** Blocks [Dictation] scheduled with its deadline, run by the test. */
    private val later = mutableListOf<Pair<Long, () -> Unit>>()

    private fun setUp(kind: RecognizerKind = RecognizerKind.OnDevice): Triple<Dictation, FakeRecognizer, () -> String> {
        val fake = FakeRecognizer()
        var draft = ""
        val d = Dictation(kind, { fake }, setDraft = { draft = it }, after = { ms, block -> later += ms to block })
        return Triple(d, fake) { draft }
    }

    @Test
    fun `an empty final after partials keeps what was shown`() {
        val (d, fake, draft) = setUp()
        d.start("", "en")
        fake.last.partial("book a table")
        d.finish()
        fake.last.final("")
        assertEquals("book a table", draft())
        assertEquals(DictationStatus.Done, d.status.value)
    }

    @Test
    fun `nothing more heard after partials isn't a failure`() {
        val (d, fake, draft) = setUp()
        d.start("", "en")
        fake.last.partial("book a table")
        fake.last.error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals("book a table", draft())
        assertEquals(DictationStatus.Done, d.status.value)
    }

    @Test
    fun `a client error answering our own stop ends quietly, one without a stop is a failure`() {
        val (d, fake, _) = setUp()
        d.start("", "en")
        d.finish()
        fake.last.error(SpeechRecognizer.ERROR_CLIENT)
        assertEquals(DictationStatus.Idle, d.status.value)
        d.start("", "en")
        fake.last.error(SpeechRecognizer.ERROR_CLIENT)
        assertEquals(DictationStatus.Failed(DictationFailure.Other, RecognizerKind.OnDevice), d.status.value)
    }

    @Test
    fun `a recognizer that never answers the stop is let go after the deadline`() {
        val (d, fake, draft) = setUp()
        d.start("", "en")
        fake.last.partial("hello")
        d.finish()
        assertTrue(d.listening)
        assertEquals(Dictation.FINISH_DEADLINE_MS, later.single().first)
        later.single().second()
        assertFalse(d.listening)
        assertTrue(fake.cancels > 0)
        assertEquals("hello", draft())
        assertEquals(DictationStatus.Done, d.status.value)
    }

    @Test
    fun `a deadline from an earlier run doesn't end the next one`() {
        val (d, fake, _) = setUp()
        d.start("", "en")
        d.finish()
        fake.last.final("one")
        d.start("one", "en")
        later.single().second()
        assertTrue(d.listening)
    }

    @Test
    fun `partial results show after the draft and the final one replaces them`() {
        val (d, fake, draft) = setUp()
        d.start("Find", "de")
        assertEquals(listOf("de"), fake.languages)
        assertEquals(DictationStatus.Listening(RecognizerKind.OnDevice, hearing = false), d.status.value)
        fake.last.ready()
        assertEquals(DictationStatus.Listening(RecognizerKind.OnDevice, hearing = true), d.status.value)
        fake.last.partial("cheap")
        assertEquals("Find cheap", draft())
        fake.last.partial("cheap flights")
        assertEquals("Find cheap flights", draft())
        fake.last.final("cheap flights to Rome")
        assertEquals("Find cheap flights to Rome", draft())
        assertEquals(DictationStatus.Done, d.status.value)
    }

    @Test
    fun `the microphone button finishes, keeping what's heard`() {
        val (d, fake, draft) = setUp()
        d.start("", "en")
        fake.last.partial("hello")
        d.finish()
        assertEquals(1, fake.stops)
        assertTrue(d.listening)
        fake.last.final("hello there")
        assertEquals("hello there", draft())
        assertFalse(d.listening)
    }

    @Test
    fun `nothing heard leaves the draft as it was`() {
        val (d, fake, draft) = setUp()
        d.start("Draft", "en")
        fake.last.error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals("", draft()) // never written
        assertEquals(DictationStatus.Failed(DictationFailure.NoSpeech, RecognizerKind.OnDevice), d.status.value)
    }

    @Test
    fun `a failure keeps the text already shown and switches to nothing else`() {
        val (d, fake, draft) = setUp(RecognizerKind.OnDevice)
        d.start("", "ja")
        fake.last.partial("konnichiwa")
        fake.last.error(12)
        assertEquals("konnichiwa", draft())
        assertEquals(DictationStatus.Failed(DictationFailure.Language, RecognizerKind.OnDevice), d.status.value)
        assertEquals(1, fake.languages.size)
    }

    @Test
    fun `the user's own edit stops dictation and a late result can't overwrite it`() {
        val (d, fake, draft) = setUp()
        d.start("Hi", "en")
        val run = fake.last
        run.partial("there")
        assertEquals("Hi there", draft())
        // Writing back what dictation wrote isn't an edit.
        d.edited("Hi there")
        assertTrue(d.listening)
        d.edited("Hi there!")
        assertFalse(d.listening)
        assertTrue(fake.cancels > 0)
        run.partial("there friend")
        run.final("there friend")
        assertEquals("Hi there", draft())
        assertEquals(DictationStatus.Idle, d.status.value)
    }

    @Test
    fun `a cancelled run's late callbacks are dropped, even once a new run started`() {
        val (d, fake, draft) = setUp()
        d.start("", "en")
        val old = fake.last
        d.cancel()
        d.start("New chat", "en")
        old.final("from the old chat")
        old.error(SpeechRecognizer.ERROR_CLIENT)
        assertEquals("", draft())
        assertTrue(d.listening)
        fake.last.final("hello")
        assertEquals("New chat hello", draft())
    }

    @Test
    fun `a refused permission says so, and the next tap hides it`() {
        val (d, _, _) = setUp(RecognizerKind.Service)
        d.refused()
        assertEquals(DictationStatus.Failed(DictationFailure.Permission, RecognizerKind.Service), d.status.value)
        d.dismiss()
        assertEquals(DictationStatus.Idle, d.status.value)
        d.refused()
        d.edited("typed instead")
        assertEquals(DictationStatus.Idle, d.status.value)
    }

    @Test
    fun `a recognizer that can't be made fails without listening`() {
        var draft = "x"
        val d = Dictation(RecognizerKind.OnDevice, { throw IllegalStateException() }, setDraft = { draft = it })
        d.start("x", "en")
        assertEquals(DictationStatus.Failed(DictationFailure.Other, RecognizerKind.OnDevice), d.status.value)
        assertEquals("x", draft)
    }

    @Test
    fun `destroy lets go of the recognizer`() {
        val (d, fake, _) = setUp()
        d.start("", "en")
        d.destroy()
        assertFalse(d.listening)
        assertTrue(fake.destroyed)
    }
}
