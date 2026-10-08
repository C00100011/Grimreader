package com.vdelaar.mylibby.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.network.CertInfo

/** Shows a server certificate the system does not know so the user can compare the fingerprint and decide. */
@Composable
fun TrustCertificateDialog(cert: CertInfo, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val trusted = stringResource(R.string.cert_trusted_toast)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cert_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cert_trust_body, cert.host))
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(cert.fingerprint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text(stringResource(R.string.cert_issuer, cert.issuer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.cert_expires, cert.validUntil), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onTrust(); Toast.makeText(context, trusted, Toast.LENGTH_LONG).show() }) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
