package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.compose.FormDropdownField
import com.v2ray.ang.compose.FormTextField
import com.v2ray.ang.compose.SettingsSwitchItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.NaiveConfigValidator

class ServerNaiveActivity : BaseServerActivity() {
    override val serverConfigType: EConfigType = EConfigType.NAIVE

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }.apply {
            configType = EConfigType.NAIVE
        }

        ServerEditorScaffold(
            title = stringResource(R.string.server_naive_title),
            onSaveClick = {
                if (validateEditorState(uiState)) {
                    saveServer(uiState)
                }
            }
        ) {
            item { CommonBasicFields(uiState) }
            item { AuthenticationFields(uiState) }
            item { TransportFields(uiState) }
            item { ExtraHeadersFields(uiState) }
            item { UdpOverTcpFields(uiState) }
            item { TlsFields(uiState) }
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val error = NaiveConfigValidator.validate(config) ?: return true
        toast(error)
        return false
    }

    private fun validateEditorState(state: ServerUiState): Boolean {
        if ((state.naiveInsecureConcurrency.toIntOrNull() ?: 0) < 1) {
            toast(R.string.server_naive_invalid_concurrency)
            return false
        }
        if (state.naiveExtraHeaders.keys.any { it.isBlank() }) {
            toast(R.string.server_naive_invalid_header_name)
            return false
        }
        return true
    }

    @Composable
    private fun AuthenticationFields(state: ServerUiState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormTextField(
                label = stringResource(R.string.server_naive_username),
                value = state.username,
                onValueChange = { state.username = it }
            )
            FormTextField(
                label = stringResource(R.string.server_naive_password),
                value = state.password,
                onValueChange = { state.password = it },
                isPassword = true
            )
        }
    }

    @Composable
    private fun TransportFields(state: ServerUiState) {
        val httpsLabel = stringResource(R.string.server_naive_transport_https)
        val quicLabel = stringResource(R.string.server_naive_transport_quic)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormDropdownField(
                label = stringResource(R.string.server_naive_transport),
                value = if (state.naiveTransport == "quic") quicLabel else httpsLabel,
                options = listOf(httpsLabel, quicLabel),
                onValueChange = { selected ->
                    state.naiveTransport = if (selected == quicLabel) "quic" else "https"
                    if (state.naiveTransport == "quic") {
                        state.naiveInsecureConcurrency = "1"
                    }
                }
            )
            FormTextField(
                label = stringResource(R.string.server_naive_insecure_concurrency),
                value = state.naiveInsecureConcurrency,
                onValueChange = { state.naiveInsecureConcurrency = it },
                enabled = state.naiveTransport != "quic",
                keyboardType = KeyboardType.Number
            )
            if (state.naiveTransport == "quic") {
                FormDropdownField(
                    label = stringResource(R.string.server_naive_quic_congestion_control),
                    value = state.naiveQuicCongestionControl,
                    options = listOf("", "bbr", "bbr2", "cubic", "reno"),
                    onValueChange = { state.naiveQuicCongestionControl = it },
                    placeholder = stringResource(R.string.server_naive_core_default)
                )
            }
        }
    }

    @Composable
    private fun ExtraHeadersFields(state: ServerUiState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.server_naive_extra_headers),
                style = MaterialTheme.typography.titleMedium
            )
            state.naiveExtraHeaders.entries.forEach { (name, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { updateHeaderName(state, name, it) },
                        label = { Text(stringResource(R.string.server_naive_header_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { removeHeader(state, name) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = stringResource(R.string.server_naive_remove_header)
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { updateHeaderValue(state, name, it) },
                    label = { Text(stringResource(R.string.server_naive_header_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(
                onClick = { addHeader(state) },
                enabled = state.naiveExtraHeaders.keys.none { it.isBlank() }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24dp),
                    contentDescription = null
                )
                Text(
                    text = stringResource(R.string.server_naive_add_header),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun UdpOverTcpFields(state: ServerUiState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSwitchItem(
                title = stringResource(R.string.server_naive_udp_over_tcp),
                summary = stringResource(R.string.server_naive_udp_over_tcp_summary),
                checked = state.naiveUdpOverTcp,
                onCheckedChange = { state.naiveUdpOverTcp = it }
            )
            if (state.naiveUdpOverTcp) {
                FormDropdownField(
                    label = stringResource(R.string.server_naive_udp_over_tcp_version),
                    value = "v${state.naiveUdpOverTcpVersion}",
                    options = listOf("v2", "v1"),
                    onValueChange = { state.naiveUdpOverTcpVersion = it.removePrefix("v") }
                )
            } else {
                Text(
                    text = stringResource(R.string.server_naive_udp_disabled_warning),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    @Composable
    private fun TlsFields(state: ServerUiState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormTextField(
                label = stringResource(R.string.server_lab_sni),
                value = state.sni,
                onValueChange = { state.sni = it },
                placeholder = state.address
            )
            FormTextField(
                label = stringResource(R.string.server_naive_trusted_root_certificates),
                value = state.naiveTrustedRootCertificates,
                onValueChange = { state.naiveTrustedRootCertificates = it },
                maxLines = 12
            )
            SettingsSwitchItem(
                title = stringResource(R.string.server_naive_ech_enabled),
                summary = stringResource(R.string.server_naive_ech_summary),
                checked = state.naiveEchEnabled,
                onCheckedChange = { state.naiveEchEnabled = it }
            )
            if (state.naiveEchEnabled) {
                FormTextField(
                    label = stringResource(R.string.server_naive_ech_config),
                    value = state.naiveEchConfig,
                    onValueChange = { state.naiveEchConfig = it },
                    maxLines = 8
                )
                FormTextField(
                    label = stringResource(R.string.server_naive_ech_query_server_name),
                    value = state.naiveEchQueryServerName,
                    onValueChange = { state.naiveEchQueryServerName = it }
                )
                FormTextField(
                    label = stringResource(R.string.server_naive_ech_dns_server),
                    value = state.naiveEchDnsServer,
                    onValueChange = { state.naiveEchDnsServer = it },
                    keyboardType = KeyboardType.Uri
                )
            }
        }
    }

    private fun addHeader(state: ServerUiState) {
        state.naiveExtraHeaders = LinkedHashMap(state.naiveExtraHeaders).apply { put("", "") }
    }

    private fun removeHeader(state: ServerUiState, name: String) {
        state.naiveExtraHeaders = LinkedHashMap(state.naiveExtraHeaders).apply { remove(name) }
    }

    private fun updateHeaderName(state: ServerUiState, oldName: String, newName: String) {
        if (newName != oldName && state.naiveExtraHeaders.keys.any {
                it != oldName && it.equals(newName, ignoreCase = true)
            }
        ) {
            return
        }
        val updated = linkedMapOf<String, String>()
        state.naiveExtraHeaders.forEach { (name, value) ->
            updated[if (name == oldName) newName else name] = value
        }
        state.naiveExtraHeaders = updated
    }

    private fun updateHeaderValue(state: ServerUiState, name: String, value: String) {
        state.naiveExtraHeaders = LinkedHashMap(state.naiveExtraHeaders).apply { put(name, value) }
    }
}
