package com.subreax.schedule.data.repository.update

import com.subreax.schedule.data.model.AppUpdateInfo
import com.subreax.schedule.data.network.NetworkStatusProvider
import com.subreax.schedule.utils.Resource
import com.subreax.schedule.utils.UiText

class UpdateRepositoryImpl(
    @Suppress("UNUSED_PARAMETER") networkStatusProvider: NetworkStatusProvider
) : UpdateRepository {
    override suspend fun getLatestRelease(): Resource<AppUpdateInfo> {
        return Resource.Failure(UiText.hardcoded("This build can't search for updates"))
    }
}
