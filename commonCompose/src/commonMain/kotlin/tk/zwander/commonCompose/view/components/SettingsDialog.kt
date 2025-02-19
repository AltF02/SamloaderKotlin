package tk.zwander.commonCompose.view.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import com.soywiz.korio.util.OS
import tk.zwander.common.res.Strings

@Composable
fun SettingsDialog(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit
) {
    val settings = remember { Settings() }

    var checked by remember { mutableStateOf(settings.getBoolean("useNativeFileDialog", false)) }

    AlertDialogDef(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        buttons = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(text = Strings.ok)
            }
        },
        title = {
            Text(Strings.settings)
        },
        text = {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                if (OS.isJvm && !OS.isAndroid) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(Strings.useNativeFilePicker)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = checked,
                            onCheckedChange = {
                                checked = it
                                settings.putBoolean("useNativeFileDialog", it)
                            }
                        )
                    }
                }
            }
        }
    )
}