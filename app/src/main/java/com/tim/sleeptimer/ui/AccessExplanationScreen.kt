package com.tim.sleeptimer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tim.sleeptimer.AccessKind
import com.tim.sleeptimer.R

/**
 * The one explanation screen shown before Android's grant page (V2_PLAN "Special access", D15/D16).
 * For accessibility this is Play's prominent disclosure: shown in normal use, before the Settings
 * page, and it needs an affirmative tap ("Agree and open settings"); Cancel changes nothing.
 */
@Composable
fun AccessExplanationScreen(kind: AccessKind, onAgree: () -> Unit, onCancel: () -> Unit, goodnight: Boolean = false) {
    // P6: Modify system settings asked for by Goodnight (brightness) rather than by "Turn screen off sooner".
    val (title, body, hint) = if (kind == AccessKind.WRITE_SETTINGS && goodnight) {
        Triple(R.string.access_title_write_goodnight, R.string.access_body_write_goodnight, null)
    } else when (kind) {
        AccessKind.ACCESSIBILITY ->
            Triple(R.string.access_title_accessibility, R.string.access_body_accessibility, R.string.access_hint_accessibility)
        AccessKind.NOTIFICATION_LISTENER ->
            Triple(R.string.access_title_listener, R.string.access_body_listener, R.string.access_hint_listener)
        AccessKind.WRITE_SETTINGS -> Triple(R.string.access_title_write, R.string.access_body_write, null)
        AccessKind.DND_POLICY -> Triple(R.string.access_title_dnd, R.string.access_body_dnd, null)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().testTag("access_agree")) {
                Text(stringResource(R.string.access_agree))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("access_cancel")) {
                Text(stringResource(R.string.access_cancel))
            }
        }
    }
}
