package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.FiatCurrency
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultProtection

/**
 * Wallet settings (W1, W2, W4): what used to sit on the wallet home as
 * equal cards, one tap from the home's gear — Security, Backup,
 * Connected sites, Site payments, Publishing and Remove wallet. The page
 * is only the frame: [WalletScreen] fills [content] with these sections,
 * since it holds the state they act on. [error] is the wallet's error
 * line, shown here too so an action taken on this page reports here;
 * [overlay] draws over the list (the Undo snackbar for Disconnect/Revoke).
 */
@Composable
internal fun WalletSettingsPage(
    error: String?,
    onBack: () -> Unit,
    title: String = stringResource(R.string.wallet_settings_title),
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = title, onDismiss = onBack) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize().testTag("wallet-settings"),
            ) {
                error?.let { item("error") { WalletErrorText(it) } }
                content()
            }
            overlay()
        }
    }
}

/**
 * Security (W2): whether the wallet is locked now, what unlocks it, where
 * its key is kept and when it locks by itself — once the home's first
 * card. Without a screen lock, what that means and the way to fix it.
 */
@Composable
internal fun WalletSecuritySection(info: Vault.Info, locked: Boolean) {
    SectionCard(title = stringResource(R.string.wallet_settings_security)) {
        if (locked) {
            StatusLine(
                icon = Icons.Filled.Lock,
                color = Color(0xFF94A3B8),
                title = stringResource(R.string.wallet_summary_locked),
                detail = if (info.protection == VaultProtection.SCREEN_LOCK) {
                    stringResource(R.string.wallet_locked_detail)
                } else {
                    stringResource(R.string.wallet_locked_detail_no_screen_lock)
                },
            )
        } else {
            StatusLine(
                icon = Icons.Filled.LockOpen,
                color = Color(0xFF22C55E),
                title = stringResource(R.string.wallet_summary_unlocked),
                detail = stringResource(R.string.wallet_settings_lock_icon_hint),
            )
        }
        Spacer(Modifier.height(8.dp))
        DetailRow(
            stringResource(R.string.wallet_detail_unlock_with),
            stringResource(
                if (info.protection == VaultProtection.SCREEN_LOCK) {
                    R.string.wallet_detail_unlock_with_screen_lock
                } else {
                    R.string.wallet_detail_unlock_with_nothing
                },
            ),
            singleLine = false,
        )
        DetailRow(
            stringResource(R.string.wallet_detail_key_kept_in),
            stringResource(if (info.strongBox) R.string.wallet_detail_key_strongbox else R.string.wallet_detail_key_keystore),
            singleLine = false,
        )
        DetailRow(stringResource(R.string.wallet_settings_auto_lock), stringResource(R.string.wallet_unlocked_detail), singleLine = false)
        if (info.protection == VaultProtection.DEVICE_ONLY) {
            Spacer(Modifier.height(8.dp))
            NoScreenLockWarning(text = stringResource(R.string.wallet_no_screen_lock_detail))
            ScreenLockSettingsButton()
        }
    }
}

/**
 * The Backup row's two lines (W4): whether the recovery phrase has been
 * checked, and Google backup's short status ([googleBackupStatus]).
 */
internal fun walletBackupRowSubtitle(backedUp: Boolean): String =
    if (backedUp) Strings.get(R.string.wallet_settings_backup_checked) else BACKUP_REMINDER

/** Backup (W4): one row for the recovery phrase and Google backup, opening [WalletBackupPage]'s content. */
@Composable
internal fun WalletBackupRow(backedUp: Boolean, googleStatus: String, enabled: Boolean, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_settings_backup)) {
        PageRow(
            title = stringResource(R.string.wallet_settings_backup_row),
            subtitle = walletBackupRowSubtitle(backedUp),
            thirdLine = stringResource(R.string.wallet_settings_google_line, googleStatus),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Shield,
            enabled = enabled,
            onClick = onOpen,
            modifier = Modifier.testTag("wallet-backup-row"),
        )
    }
}

/**
 * The Backup page's recovery-phrase card (W4): where the phrase is kept
 * ([walletBackupDetail]) and Show recovery phrase, which runs the backup
 * check while it hasn't passed (#421).
 */
@Composable
internal fun WalletPhraseSection(info: Vault.Info, keptOn: String, enabled: Boolean, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_phrase_section)) {
        DetailRow(stringResource(R.string.wallet_settings_kept_on), keptOn, singleLine = false)
        if (!info.backedUp) {
            Text(BACKUP_REMINDER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        PageRow(
            title = stringResource(R.string.wallet_show_phrase),
            subtitle = if (info.protection == VaultProtection.SCREEN_LOCK) {
                stringResource(R.string.wallet_phrase_asks)
            } else {
                stringResource(R.string.wallet_phrase_opens_without_asking)
            },
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Key,
            enabled = enabled,
            onClick = onOpen,
        )
    }
}

/** Connected sites with none connected: say so, so the section is never just missing. */
@Composable
internal fun NoConnectedSitesSection() {
    SectionCard(title = stringResource(R.string.wallet_settings_sites)) {
        Text(
            stringResource(R.string.wallet_settings_no_sites),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Publishing: the row into Publisher identities. */
@Composable
internal fun WalletPublishingSection(sites: Int, enabled: Boolean, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_publishing_section)) {
        PageRow(
            title = PUBLISHER_IDENTITIES_TITLE,
            subtitle = publisherIdentitiesSummary(sites),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Badge,
            enabled = enabled,
            onClick = onOpen,
        )
    }
}

/** Remove wallet, last on the page: its confirmation is [WalletScreen]'s. */
@Composable
internal fun RemoveWalletSection(enabled: Boolean, onRemove: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_remove_section)) {
        PageRow(
            title = stringResource(R.string.wallet_remove_title),
            subtitle = stringResource(R.string.wallet_remove_subtitle),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.DeleteForever,
            enabled = enabled,
            onClick = onRemove,
            modifier = Modifier.testTag("wallet-remove-row"),
        )
    }
}

/**
 * Show prices (#439): Off (the default), Euro or US dollar. Off means no
 * price is looked up at all; on, the wallet shows approximate values
 * under its amounts, read from price records on the chain itself.
 */
@Composable
internal fun WalletPricesSection(selected: FiatCurrency, onSelect: (FiatCurrency) -> Unit) {
    SectionCard(title = stringResource(R.string.fiat_setting_title)) {
        Text(
            stringResource(R.string.fiat_setting_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Column(Modifier.selectableGroup().testTag("wallet-prices")) {
            FiatCurrency.entries.forEach { c ->
                EngineRadioRow(label = fiatChoiceLabel(c), selected = c == selected, onClick = { onSelect(c) })
            }
        }
    }
}
