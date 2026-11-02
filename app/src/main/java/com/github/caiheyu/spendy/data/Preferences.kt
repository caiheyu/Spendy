package com.github.caiheyu.spendy.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as PreferencesData
import com.github.caiheyu.spendy.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

val LedgerJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
private val Context.settingsStore by preferencesDataStore("spendy_settings")

@Singleton
class Preferences(private val store: DataStore<PreferencesData>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.settingsStore)
    private val settingsKey = stringPreferencesKey("settings")
    private val draftKey = stringPreferencesKey("entry_draft")
    val settings: Flow<AppSettings> = store.data.map { p -> p[settingsKey]?.let { LedgerJson.decodeFromString<AppSettings>(it) } ?: AppSettings() }
    val draft: Flow<EntryDraft?> = store.data.map { p -> p[draftKey]?.let { LedgerJson.decodeFromString<EntryDraft>(it) } }
    suspend fun setSettings(settings: AppSettings) { store.edit { it[settingsKey] = LedgerJson.encodeToString(settings) } }
    suspend fun change(transform: (AppSettings) -> AppSettings) {
        store.edit { p -> p[settingsKey] = LedgerJson.encodeToString(transform(p[settingsKey]?.let { LedgerJson.decodeFromString<AppSettings>(it) } ?: AppSettings())) }
    }
    suspend fun saveDraft(draft: EntryDraft?) { store.edit { if (draft == null) it.remove(draftKey) else it[draftKey] = LedgerJson.encodeToString(draft) } }
}
