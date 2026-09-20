package com.subreax.schedule.ui

import android.content.Context
import android.util.Log
import com.subreax.schedule.data.model.Schedule
import com.subreax.schedule.data.model.ScheduleId
import com.subreax.schedule.data.model.ScheduleType
import com.subreax.schedule.data.model.Settings
import com.subreax.schedule.data.repository.settings.SettingsRepository
import com.subreax.schedule.data.usecase.ScheduleUseCases
import com.subreax.schedule.ui.component.schedule.item.ScheduleItem
import com.subreax.schedule.ui.component.schedule.item.toScheduleItems
import com.subreax.schedule.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

enum class SyncType {
    None, IfExpired, Force, Cancel
}

private data class ScheduleSettings(
    val alwaysShowSubjectBeginTime: Boolean
)

class ScheduleContainer(
    private val scheduleUseCases: ScheduleUseCases,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val _schedule = MutableStateFlow(UiSchedule(nullScheduleId()))
    val schedule = _schedule.asStateFlow()

    private val _uiLoadingState = MutableStateFlow<UiLoadingState>(UiLoadingState.Loading)
    val loadingState = _uiLoadingState.asStateFlow()

    private var currentScheduleId = ""

    private val updateRequests = Channel<Pair<String, SyncType>>(capacity = 8)

    private var scheduleSettings = settingsRepository.settings.value.toScheduleSettings()

    init {
        coroutineScope.launch {
            var isFirstUpdate = true
            var activeSyncType = SyncType.None

            var updateJob = cancelledJob()
            while (isActive) {
                var (id, syncType) = updateRequests.receive()
                Log.d("ScheduleContainer", "request ($id, $syncType)")

                if (isFirstUpdate) {
                    subscribeToDataChanges()
                    isFirstUpdate = false
                }

                if (currentScheduleId == id && syncType == SyncType.IfExpired && isPrevLoadingFailed()) {
                    syncType = SyncType.Cancel
                }

                val syncType1 = maxOf(syncType, activeSyncType)
                updateJob.cancel()
                updateJob = coroutineScope.launch {
                    activeSyncType = syncType1
                    updateDirect(id, syncType1, scheduleSettings)
                    if (isActive) {
                        activeSyncType = SyncType.None
                    }
                }
            }
        }
    }

    fun update(id: String, syncType: SyncType = SyncType.IfExpired) {
        updateRequests.trySend(Pair(id, syncType))
    }

    fun resetSchedule() {
        coroutineScope.launch {
            scheduleUseCases.clear(currentScheduleId)
            update(currentScheduleId, SyncType.Force)
        }
    }

    private fun subscribeToDataChanges() {
        coroutineScope.launch {
            settingsRepository.settings.drop(1).collect {
                if (currentScheduleId.isEmpty()) {
                    return@collect
                }

                val newSettings = it.toScheduleSettings()
                if (scheduleSettings != newSettings) {
                    scheduleSettings = newSettings
                    update(currentScheduleId, SyncType.IfExpired)
                }
            }
        }
    }

    private suspend fun updateDirect(
        id: String,
        syncType: SyncType,
        settings: ScheduleSettings
    ) = coroutineScope {
        currentScheduleId = id
        _uiLoadingState.value = UiLoadingState.Loading

        Log.d("ScheduleContainer", "* load ($id, $syncType)")
        val res = when (syncType) {
            SyncType.None,
            SyncType.Cancel -> scheduleUseCases.get(id)
            SyncType.IfExpired -> scheduleUseCases.syncIfExpiredAndGet(id)
            SyncType.Force -> scheduleUseCases.syncAndGet(id)
        }

        ensureActive()

        _schedule.value = res.toUiSchedule(settings.alwaysShowSubjectBeginTime)

        ensureActive()

        _uiLoadingState.value = when {
            res is Resource.Failure -> {
                UiLoadingState.Error(res.message)
            }
            syncType == SyncType.Cancel -> {
                UiLoadingState.Cancelled
            }
            else -> {
                UiLoadingState.Ready
            }
        }
    }

    private fun isPrevLoadingFailed(): Boolean {
        return _uiLoadingState.value.let { it is UiLoadingState.Error || it is UiLoadingState.Cancelled }
    }

    private fun Resource<Schedule>.toUiSchedule(alwaysShowSubjectBeginTime: Boolean): UiSchedule {
        return if (this is Resource.Success) {
            value
        } else {
            (this as Resource.Failure).cachedValue
        }?.toUiSchedule(alwaysShowSubjectBeginTime) ?: UiSchedule()
    }

    private fun Schedule.toUiSchedule(alwaysShowSubjectBeginTime: Boolean): UiSchedule {
        val (items, todayItemIndex) = this.subjects.toScheduleItems(
            context,
            id.type,
            alwaysShowSubjectBeginTime
        )
        return UiSchedule(
            id = id,
            items = items,
            syncTime = syncTime,
            todayItemIndex = todayItemIndex.coerceAtLeast(0)
        )
    }

    private fun maxOf(st1: SyncType, st2: SyncType): SyncType {
        return SyncType.entries[maxOf(st1.ordinal, st2.ordinal)]
    }

    companion object {
        private fun cancelledJob(): Job = Job().also { it.cancel() }
    }
}

data class UiSchedule(
    val id: ScheduleId = nullScheduleId(),
    val items: List<ScheduleItem> = emptyList(),
    val syncTime: Date = Date(),
    val todayItemIndex: Int = 0
)

private fun nullScheduleId(networkId: String = "") = ScheduleId(
    networkId, ScheduleType.Unknown
)

private fun Settings.toScheduleSettings(): ScheduleSettings {
    return ScheduleSettings(alwaysShowSubjectBeginTime)
}