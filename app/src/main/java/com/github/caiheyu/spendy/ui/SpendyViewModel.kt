package com.github.caiheyu.spendy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SpendyViewModel @Inject constructor(val repository: LedgerRepository, val preferences: Preferences, val updates: UpdateService) : ViewModel() {
    val availableUpdate = MutableStateFlow<UpdateManifest?>(null)
    val updateApk = MutableStateFlow<java.io.File?>(null)
    var pendingFile: ExportDocument? = null
    val ledger = repository.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val savedDraft = preferences.draft.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val canUndo = repository.canUndo.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val canUndoRestore = repository.canUndoRestore.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val fatal = MutableStateFlow<String?>(null)
    val editor = MutableStateFlow<EntryDraft?>(null)
    val editorError = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val messages = Channel<String>(Channel.BUFFERED)
    private var draftJob: Job? = null
    init {
        viewModelScope.launch { runCatching { repository.initialize() }.onFailure { fatal.value = it.message ?: "账本加载失败" } }
        viewModelScope.launch(Dispatchers.IO) { runCatching { updates.check() }.onSuccess { availableUpdate.value = it } }
    }
    fun checkUpdate(done: () -> Unit) = action(done = done) {
        updateApk.value = null
        availableUpdate.value = updates.check()
        if (availableUpdate.value == null) message("当前已是最新版本")
    }
    fun message(text: String) { messages.trySend(text) }
    fun action(success: String? = null, done: () -> Unit = {}, onError: (String) -> Unit = ::message, block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { withContext(Dispatchers.IO) { block() }; success?.let { message(it) }; done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (com.github.caiheyu.spendy.BuildConfig.DEBUG) android.util.Log.e("Spendy", "Operation failed", e)
                onError(e.message ?: "操作失败，请重试")
            }
            finally { busy.value = false }
        }
    }
    fun changeSettings(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { runCatching { preferences.change(transform) }.onFailure { message("设置保存失败") } } }
    fun openEditor(e: Entry? = null, copy: Boolean = false, kind: String? = null) {
        val s = ledger.value ?: return
        editorError.value = null
        editor.value = when {
            kind != null && e != null -> EntryDraft(kind = kind, expression = Money.decimal(e.amountMinor - Ledger.recovered(s, e.id)),
                accountId = s.accounts.firstOrNull { !it.archived }?.id.orEmpty(), categoryId = e.categoryId,
                subcategoryId = e.subcategoryId, tagIds = e.tagIds, originalId = e.id, countInStats = e.countInStats)
            e != null -> EntryDraft.from(e).let { if (copy) it.copy(id = null, date = java.time.LocalDate.now().toString(), kind = "normal", originalId = null,
                tagIds = it.tagIds.filter { id -> s.tags.any { t -> t.id == id && !t.archived } }) else it }
            else -> savedDraft.value ?: EntryDraft(accountId = s.accounts.firstOrNull { !it.archived }?.id.orEmpty())
        }
        if (copy) message("已复制为今天的新账单，请核对金额和账户")
    }
    fun updateDraft(draft: EntryDraft) {
        editor.value = draft
        editorError.value = null
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(250); preferences.saveDraft(draft) }
    }
    fun leaveEditor(keep: Boolean, done: () -> Unit) {
        draftJob?.cancel()
        action(done = { editor.value = null; done() }) { preferences.saveDraft(if (keep) editor.value else null) }
    }
    fun saveEditor(again: Boolean, done: () -> Unit) {
        val draft = editor.value ?: return
        editorError.value = null
        draftJob?.cancel()
        action("已记好这一笔", done = {
            if (again) editor.value = draft.copy(id = null, expression = "", note = "", newEntryId = newId())
            else { editor.value = null; done() }
        }, onError = { editorError.value = it }) {
            val entry = draft.toEntry()
            require(entry.amountMinor > 0 || entry.kind != "normal" || entry.type == "adjustment") { "请输入大于 0 的金额" }
            repository.save(entry)
            preferences.saveDraft(null)
        }
    }
}

data class ExportDocument(val bytes: ByteArray, val fileName: String, val backupRevision: Long? = null, val entryCount: Int = 0, val preferencesHash: String = "")
