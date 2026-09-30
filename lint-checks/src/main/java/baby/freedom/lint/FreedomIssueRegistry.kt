package baby.freedom.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** Freedom's own lint checks, run by `./gradlew :app:lintDebug`. */
class FreedomIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(HardcodedUiTextDetector.ISSUE)

    override val api: Int = CURRENT_API

    // The oldest lint API these checks work with: the one they're built against.
    override val minApi: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "Freedom Browser",
        identifier = "baby.freedom.lint",
        feedbackUrl = "https://github.com/solardev-xyz/freedom-browser-android/issues",
    )
}
