package com.gluview.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * De quem é o dado (redesign visual, Fase 4): no modo seguidor, Histórico e Relatório mostram o
 * paciente ativo (os ViewModels buscam pelo activeFollowedPatientId); aqui só o NOME, para a tela
 * dizer isso. null = o próprio usuário (paciente) ou seguidor sem paciente escolhido.
 */
@HiltViewModel
class FollowedSubjectViewModel @Inject constructor(preferences: AppPreferences) : ViewModel() {
    val name: StateFlow<String?> = combine(preferences.isFollowerOnly, preferences.activeFollowedPatientName) { follower, name ->
        if (follower) name.ifBlank { null } else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun rememberFollowedSubject(vm: FollowedSubjectViewModel = hiltViewModel()): String? {
    val name by vm.name.collectAsStateWithLifecycle()
    return name
}

/** Faixa "Mostrando os dados de Maria", logo abaixo da barra do topo. */
@Composable
fun FollowedSubjectBanner(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.followed_subject_banner, name), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
