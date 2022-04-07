/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.downloads.impl

import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import com.duckduckgo.downloads.api.DownloadCallback
import com.duckduckgo.downloads.api.DownloadCommand
import com.duckduckgo.downloads.api.DownloadFailReason
import com.duckduckgo.downloads.api.DownloadFailReason.*
import com.duckduckgo.downloads.api.model.DownloadItem
import com.duckduckgo.downloads.api.model.DownloadStatus
import com.duckduckgo.downloads.impl.pixels.DownloadsPixelName
import com.duckduckgo.downloads.store.DownloadsRepository
import com.duckduckgo.app.di.AppCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@ContributesBinding(AppScope::class)
@SingleInstanceIn(AppScope::class)
class FileDownloadCallback @Inject constructor(
    private val fileDownloadNotificationManager: FileDownloadNotificationManager,
    private val downloadsRepository: DownloadsRepository,
    private val pixel: Pixel,
    private val dispatchers: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope
) : DownloadCallback {

    private val command = Channel<DownloadCommand>(1, BufferOverflow.DROP_OLDEST)

    override fun onStart(downloadItem: DownloadItem) {
        Timber.d("Download started for file ${downloadItem.fileName}")
        pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_STARTED)
        val downloadStartedMessage = DownloadCommand.ShowDownloadStartedMessage(
            messageId = R.string.downloadsDownloadStartedMessage,
            showNotification = downloadItem.downloadId == 0L,
            fileName = downloadItem.fileName
        )
        if (downloadStartedMessage.showNotification) {
            fileDownloadNotificationManager.showDownloadInProgressNotification()
        }
        appCoroutineScope.launch(dispatchers.io()) {
            command.send(downloadStartedMessage)
            downloadsRepository.insert(downloadItem)
        }
    }

    override fun onSuccess(downloadId: Long, contentLength: Long) {
        Timber.d("Download succeeded for file with downloadId $downloadId")
        pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_SUCCEEDED)
        appCoroutineScope.launch(dispatchers.io()) {
            downloadsRepository.update(downloadId = downloadId, downloadStatus = DownloadStatus.FINISHED, contentLength = contentLength)
            downloadsRepository.getDownloadItem(downloadId)?.let {
                command.send(
                    DownloadCommand.ShowDownloadSuccessMessage(
                        messageId = R.string.downloadsDownloadFinishedMessage,
                        showNotification = false,
                        fileName = it.fileName,
                        filePath = it.filePath
                    )
                )
            }
        }
    }

    override fun onSuccess(file: File, mimeType: String?) {
        Timber.d("Download succeeded for file with name ${file.name}")
        pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_SUCCEEDED)
        fileDownloadNotificationManager.showDownloadFinishedNotification(filename = file.name, uri = file.absolutePath.toUri(), mimeType = mimeType)
        appCoroutineScope.launch(dispatchers.io()) {
            downloadsRepository.update(fileName = file.name, downloadStatus = DownloadStatus.FINISHED, contentLength = file.length())
            command.send(
                DownloadCommand.ShowDownloadSuccessMessage(
                    messageId = R.string.downloadsDownloadFinishedMessage,
                    showNotification = true,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    mimeType = mimeType
                )
            )
        }
    }

    override fun onError(downloadId: Long) {
        Timber.d("Download error for file with downloadId $downloadId")
        // It happens on rare unknown occasions when the DownloadManager completes a download without a failed or success state.
        // This is considered a failed download. A failed pixel is sent and the database record is cleared.
        pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_FAILED)
        appCoroutineScope.launch(dispatchers.io()) {
            downloadsRepository.delete(listOf(downloadId))
        }
    }

    override fun onFailure(url: String?, reason: DownloadFailReason) {
        Timber.d("Failed to download file with url $url and reason $reason.")
        handleFailedDownload(showNotification = true, reason = reason)
    }

    override fun onFailOrCancel(downloadId: Long, reason: DownloadFailReason) {
        // This is a failed / cancelled download as handled by DownloadManager.
        // If the database doesn't contain a record for that downloadId it means the download was cancelled by the user from the
        // application. A cancel pixel is sent as it was the user's decision to cancel the started download.
        // If there is a record in the database it will be removed and a fail pixel sent.
        appCoroutineScope.launch(dispatchers.io()) {
            val item = downloadsRepository.getDownloadItem(downloadId)
            if (item == null) {
                Timber.d("Cancelled download file with downloadId $downloadId.")
                pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_CANCELLED)
            } else {
                Timber.d("Failed to download file with downloadId $downloadId and reason $reason.")
                downloadsRepository.delete(listOf(downloadId))
                handleFailedDownload(showNotification = false, reason = reason)
            }
        }
    }

    override fun commands(): Flow<DownloadCommand> {
        return command.receiveAsFlow()
    }

    private fun handleFailedDownload(showNotification: Boolean, reason: DownloadFailReason) {
        pixel.fire(DownloadsPixelName.DOWNLOAD_REQUEST_FAILED)
        val messageId = when (reason) {
            ConnectionRefused -> R.string.downloadsErrorMessage
            DownloadManagerDisabled -> R.string.downloadsDownloadManagerDisabledErrorMessage
            Other, UnsupportedUrlType, DataUriParseException -> R.string.downloadsDownloadGenericErrorMessage
        }
        val downloadFailedMessage = DownloadCommand.ShowDownloadFailedMessage(
            messageId = messageId,
            showNotification = showNotification,
            showEnableDownloadManagerAction = reason == DownloadManagerDisabled
        )
        if (downloadFailedMessage.showNotification) {
            fileDownloadNotificationManager.showDownloadFailedNotification()
        }
        appCoroutineScope.launch(dispatchers.io()) {
            command.send(downloadFailedMessage)
        }
    }
}
