package baby.freedom.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class HardcodedUiTextDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = HardcodedUiTextDetector()

    override fun getIssues(): List<Issue> = listOf(HardcodedUiTextDetector.ISSUE)

    private fun check(vararg files: TestFile) =
        lint().files(*stubs(), *files).allowMissingSdk().run()

    fun testComposeTextLiteral() {
        check(
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import androidx.compose.material3.Text
                import androidx.compose.foundation.text.BasicText

                @Composable fun Screen(count: Int, a: String, b: String, label: String) {
                    Text("Hello")
                    Text(text = "Named")
                    Text(modifier = 1, text = "Named after another")
                    BasicText("Basic")
                    Text("${'$'}count tabs")
                    Text("Total: " + count)
                    Text(if (count == 1) "tab" else "tabs")
                    Text(when (count) { 0 -> "none"; else -> "${'$'}count" })
                    Text(when (count) { 0 -> "·"; else -> "${'$'}count" })
                    Text("${'$'}a · ${'$'}b")
                    Text("·")
                    Text("—")
                    Text(label)
                    Text(text = label)
                    Text(stringResource(1))
                    Text("${'$'}count")
                }
                fun stringResource(id: Int): String = ""
                """
            ).indented()
        ).expect(
            """
            src/test/test.kt:7: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text("Hello")
                     ~~~~~~~
            src/test/test.kt:8: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text(text = "Named")
                            ~~~~~~~
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text(modifier = 1, text = "Named after another")
                                          ~~~~~~~~~~~~~~~~~~~~~
            src/test/test.kt:10: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                BasicText("Basic")
                          ~~~~~~~
            src/test/test.kt:11: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text("${'$'}count tabs")
                     ~~~~~~~~~~~~~
            src/test/test.kt:12: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text("Total: " + count)
                     ~~~~~~~~~~~~~~~~~
            src/test/test.kt:13: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text(if (count == 1) "tab" else "tabs")
                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/test.kt:14: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Text(when (count) { 0 -> "none"; else -> "${'$'}count" })
                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            8 errors
            """
        )
    }

    fun testOtherTextFunctionIsNotComposeText() {
        check(
            kotlin(
                """
                package test
                fun Text(s: String) = s
                fun BasicText(s: String) = s
                fun use() { Text("Not Compose"); BasicText("Neither") }
                """
            ).indented()
        ).expectClean()
    }

    fun testContentDescription() {
        check(
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import androidx.compose.material3.Icon
                import androidx.compose.ui.semantics.semantics
                import androidx.compose.ui.semantics.contentDescription

                @Composable fun Screen(desc: String) {
                    Icon(1, contentDescription = "Close")
                    Icon(1, "Back")
                    Icon(1, contentDescription = desc)
                    Icon(1, contentDescription = null)
                    semantics { contentDescription = "Loading" }
                    semantics { contentDescription = desc }
                }
                """
            ).indented()
        ).expect(
            """
            src/test/test.kt:8: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Icon(1, contentDescription = "Close")
                                             ~~~~~~~
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Icon(1, "Back")
                        ~~~~~~
            src/test/test.kt:12: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                semantics { contentDescription = "Loading" }
                                                 ~~~~~~~~~
            3 errors
            """
        )
    }

    fun testToastAndSnackbar() {
        check(
            kotlin(
                """
                package test
                import android.content.Context
                import android.widget.Toast
                import androidx.compose.material3.SnackbarHostState

                suspend fun show(context: Context, host: SnackbarHostState, msg: String) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    host.showSnackbar("Saved", "Undo")
                    host.showSnackbar(message = msg, actionLabel = "Retry")
                    host.showSnackbar(msg)
                }
                """
            ).indented()
        ).expect(
            """
            src/test/test.kt:7: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                        ~~~~~~~~
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                host.showSnackbar("Saved", "Undo")
                                  ~~~~~~~
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                host.showSnackbar("Saved", "Undo")
                                           ~~~~~~
            src/test/test.kt:10: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                host.showSnackbar(message = msg, actionLabel = "Retry")
                                                               ~~~~~~~
            4 errors
            """
        )
    }

    fun testToastInJava() {
        check(
            java(
                """
                package test;
                import android.content.Context;
                import android.widget.Toast;

                class Show {
                    void show(Context context, String msg) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                    }
                }
                """
            ).indented()
        ).expect(
            """
            src/test/Show.java:7: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
                                            ~~~~~~~~
            1 error
            """
        )
    }

    fun testNotifications() {
        check(
            kotlin(
                """
                package test
                import android.app.Notification
                import android.app.NotificationChannel
                import android.content.Context
                import androidx.core.app.NotificationCompat

                fun build(context: Context, title: String, peers: Int) {
                    NotificationCompat.Builder(context, "node")
                        .setContentTitle("Swarm node")
                        .setContentText("${'$'}peers peers")
                        .setSubText("Sub")
                        .setTicker("Ticker")
                        .addAction(0, "Stop", null)
                        .setContentTitle(title)
                        .addAction(0, title, null)
                    Notification.Builder(context, "node").setContentTitle("Platform")
                    NotificationChannel("node", "Swarm node", 2)
                    NotificationChannel("node", title, 2)
                }
                """
            ).indented()
        ).expect(
            """
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setContentTitle("Swarm node")
                                     ~~~~~~~~~~~~
            src/test/test.kt:10: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setContentText("${'$'}peers peers")
                                    ~~~~~~~~~~~~~~
            src/test/test.kt:11: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setSubText("Sub")
                                ~~~~~
            src/test/test.kt:12: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setTicker("Ticker")
                               ~~~~~~~~
            src/test/test.kt:13: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .addAction(0, "Stop", null)
                                  ~~~~~~
            src/test/test.kt:16: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                Notification.Builder(context, "node").setContentTitle("Platform")
                                                                      ~~~~~~~~~~
            src/test/test.kt:17: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                NotificationChannel("node", "Swarm node", 2)
                                            ~~~~~~~~~~~~
            7 errors
            """
        )
    }

    fun testAlertDialogBuilder() {
        check(
            kotlin(
                """
                package test
                import android.app.AlertDialog
                import android.content.Context

                fun ask(context: Context, message: String) {
                    AlertDialog.Builder(context)
                        .setTitle("Delete?")
                        .setMessage("This can't be undone")
                        .setPositiveButton("Delete", null)
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Later", null)
                        .setMessage(message)
                        .setTitle("…")
                }
                """
            ).indented()
        ).expect(
            """
            src/test/test.kt:7: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setTitle("Delete?")
                              ~~~~~~~~~
            src/test/test.kt:8: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setMessage("This can't be undone")
                                ~~~~~~~~~~~~~~~~~~~~~~
            src/test/test.kt:9: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setPositiveButton("Delete", null)
                                       ~~~~~~~~
            src/test/test.kt:10: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setNegativeButton("Cancel", null)
                                       ~~~~~~~~
            src/test/test.kt:11: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
                    .setNeutralButton("Later", null)
                                      ~~~~~~~
            5 errors
            """
        )
    }

    fun testUnrelatedCallsAreClean() {
        check(
            kotlin(
                """
                package test
                class Log { fun setTitle(s: String) {}; fun setMessage(s: String) {} }
                fun other(log: Log, map: MutableMap<String, String>) {
                    log.setTitle("Not a dialog")
                    log.setMessage("Not a dialog")
                    map["key"] = "value"
                    println("Log line")
                    require(true) { "Error message" }
                }
                """
            ).indented()
        ).expectClean()
    }

    fun testSuppressed() {
        check(
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import androidx.compose.material3.Text

                @Suppress("HardcodedUiText") // a sample address, not language
                @Composable fun Sample() {
                    Text("0x1234…abcd")
                }

                @Composable fun Inline() {
                    @Suppress("HardcodedUiText")
                    val sample = Text("0xabcd")
                }
                """
            ).indented()
        ).expectClean()
    }

    fun testTestSourcesAreNotChecked() {
        // A Gradle project, so that src/test/java is a unit test source set.
        lint().files(
            gradle("apply plugin: 'com.android.application'").indented(),
            *stubs("src/main/java"),
            kotlin(
                "src/main/java/test/Screen.kt",
                """
                package test
                import androidx.compose.material3.Text
                fun screen() { Text("In the app") }
                """
            ).indented(),
            kotlin(
                "src/test/java/test/ScreenTest.kt",
                """
                package test
                import androidx.compose.material3.Text
                fun screenTest() { Text("In a unit test") }
                """
            ).indented(),
            kotlin(
                "src/androidTest/java/test/ScreenAndroidTest.kt",
                """
                package test
                import androidx.compose.material3.Text
                fun screenAndroidTest() { Text("In an instrumented test") }
                """
            ).indented(),
        ).allowMissingSdk().run().expect(
            """
            src/main/java/test/Screen.kt:3: Error: Hardcoded UI text: move it to res/values/strings*.xml and use stringResource/Strings.get (#280) [HardcodedUiText]
            fun screen() { Text("In the app") }
                                ~~~~~~~~~~~~
            1 error
            """
        )
    }

    private companion object {
        /** API stubs, under [root] (`src` for a plain project, `src/main/java` for a Gradle one). */
        fun stubs(root: String = "src"): Array<TestFile> = arrayOf(
            kotlin(
                "$root/androidx/compose/runtime/Composable.kt",
                """
                package androidx.compose.runtime
                annotation class Composable
                """
            ).indented(),
            kotlin(
                "$root/androidx/compose/material3/Text.kt",
                """
                package androidx.compose.material3
                import androidx.compose.runtime.Composable
                @Composable fun Text(text: String, modifier: Int = 0, color: Long = 0L) {}
                """
            ).indented(),
            kotlin(
                "$root/androidx/compose/material3/Icon.kt",
                """
                package androidx.compose.material3
                import androidx.compose.runtime.Composable
                @Composable fun Icon(imageVector: Int, contentDescription: String?, modifier: Int = 0) {}
                """
            ).indented(),
            kotlin(
                "$root/androidx/compose/material3/SnackbarHost.kt",
                """
                package androidx.compose.material3
                class SnackbarHostState {
                    suspend fun showSnackbar(
                        message: String,
                        actionLabel: String? = null,
                        withDismissAction: Boolean = false,
                    ): Int = 0
                }
                """
            ).indented(),
            kotlin(
                "$root/androidx/compose/foundation/text/BasicText.kt",
                """
                package androidx.compose.foundation.text
                import androidx.compose.runtime.Composable
                @Composable fun BasicText(text: String, modifier: Int = 0) {}
                """
            ).indented(),
            kotlin(
                "$root/androidx/compose/ui/semantics/Semantics.kt",
                """
                package androidx.compose.ui.semantics
                class SemanticsPropertyReceiver
                var SemanticsPropertyReceiver.contentDescription: String
                    get() = ""
                    set(value) {}
                fun semantics(properties: SemanticsPropertyReceiver.() -> Unit) {}
                """
            ).indented(),
            java(
                "$root/android/content/Context.java",
                """
                package android.content;
                public class Context {}
                """
            ).indented(),
            java(
                "$root/android/widget/Toast.java",
                """
                package android.widget;
                import android.content.Context;
                public class Toast {
                    public static final int LENGTH_SHORT = 0;
                    public static Toast makeText(Context context, CharSequence text, int duration) { return null; }
                    public static Toast makeText(Context context, int resId, int duration) { return null; }
                    public void show() {}
                }
                """
            ).indented(),
            java(
                "$root/android/app/Notification.java",
                """
                package android.app;
                import android.content.Context;
                public class Notification {
                    public static class Builder {
                        public Builder(Context context, String channelId) {}
                        public Builder setContentTitle(CharSequence title) { return this; }
                    }
                }
                """
            ).indented(),
            java(
                "$root/android/app/NotificationChannel.java",
                """
                package android.app;
                public class NotificationChannel {
                    public NotificationChannel(String id, CharSequence name, int importance) {}
                }
                """
            ).indented(),
            java(
                "$root/android/app/PendingIntent.java",
                """
                package android.app;
                public class PendingIntent {}
                """
            ).indented(),
            java(
                "$root/androidx/core/app/NotificationCompat.java",
                """
                package androidx.core.app;
                import android.app.PendingIntent;
                import android.content.Context;
                public class NotificationCompat {
                    public static class Builder {
                        public Builder(Context context, String channelId) {}
                        public Builder setContentTitle(CharSequence title) { return this; }
                        public Builder setContentText(CharSequence text) { return this; }
                        public Builder setSubText(CharSequence text) { return this; }
                        public Builder setTicker(CharSequence text) { return this; }
                        public Builder addAction(int icon, CharSequence title, PendingIntent intent) { return this; }
                    }
                }
                """
            ).indented(),
            java(
                "$root/android/content/DialogInterface.java",
                """
                package android.content;
                public interface DialogInterface {
                    interface OnClickListener { void onClick(DialogInterface dialog, int which); }
                }
                """
            ).indented(),
            java(
                "$root/android/app/AlertDialog.java",
                """
                package android.app;
                import android.content.Context;
                import android.content.DialogInterface;
                public class AlertDialog {
                    public static class Builder {
                        public Builder(Context context) {}
                        public Builder setTitle(CharSequence title) { return this; }
                        public Builder setMessage(CharSequence message) { return this; }
                        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) { return this; }
                        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) { return this; }
                        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) { return this; }
                    }
                }
                """
            ).indented(),
        )
    }
}
