package com.ko2ic.imagedownloader

import android.app.DownloadManager
import android.app.DownloadManager.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean


class Downloader(private val context: Context, private val request: Request) {

    private val manager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var receiver: BroadcastReceiver? = null

    private var downloadId: Long? = null

    // Guards the terminal callbacks: both the broadcast and the polling thread can
    // observe the end of the download, but the Flutter result may only be answered once.
    private val finished = AtomicBoolean(false)

    fun execute(
        onNext: (DownloadStatus) -> Unit,
        onError: (DownloadFailedException) -> Unit,
        onComplete: () -> Unit
    ) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                if (ACTION_DOWNLOAD_COMPLETE == intent.action) {
                    val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                    // Ignore completions of other downloads.
                    if (id != downloadId) return
                    resolveDownloadStatus(id, onNext, onError, onComplete)
                }
            }
        }
        // ACTION_DOWNLOAD_COMPLETE is sent by the DownloadManager provider, which is not
        // part of this app, so a RECEIVER_NOT_EXPORTED receiver may never get it.
        // The polling thread below is the source of truth; the receiver is only a shortcut.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        val downloadId = manager.enqueue(request)
        this.downloadId = downloadId

        Thread(Runnable {
            while (!finished.get()) {
                val q = Query()
                q.setFilterById(downloadId)

                val cursor = manager.query(q)
                if (cursor == null || !cursor.moveToFirst()) {
                    // The download was removed (e.g. cancelled from the notification).
                    cursor?.close()
                    mainHandler.post {
                        finishWithError(onError, DownloadFailedException("DOWNLOAD_REMOVED", "The download was removed."))
                    }
                    break
                }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
                val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_BYTES_DOWNLOADED_SO_FAR))
                // -1 until the server reports the size.
                val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE_BYTES))

                if (totalBytes > 0) {
                    val progress = BigDecimal(downloadedBytes).divide(
                        BigDecimal(totalBytes), 2, RoundingMode.DOWN
                    ).multiply(
                        BigDecimal(100)
                    ).setScale(0, RoundingMode.DOWN)

                    onNext(DownloadStatus.Running(createRequestResult(downloadId, cursor), progress.toInt()))
                }

                cursor.close()

                if (status == STATUS_SUCCESSFUL || status == STATUS_FAILED) {
                    mainHandler.post { resolveDownloadStatus(downloadId, onNext, onError, onComplete) }
                    break
                }

                Thread.sleep(200)
            }
        }).start()
    }

    fun cancel() {
        downloadId?.let { manager.remove(it) }
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            receiver = null
        }
    }

    private fun finishWithError(onError: (DownloadFailedException) -> Unit, error: DownloadFailedException) {
        if (!finished.compareAndSet(false, true)) return
        unregisterReceiver()
        onError(error)
    }

    private fun resolveDownloadStatus(
        id: Long,
        onNext: (DownloadStatus) -> Unit,
        onError: (DownloadFailedException) -> Unit,
        onComplete: () -> Unit
    ) {
        val query = Query().apply {
            setFilterById(id)
        }
        val cursor = manager.query(query)
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_REASON))
            val requestResult: RequestResult = createRequestResult(id, cursor)
            when (status) {
                STATUS_FAILED -> {
                    val failedReason = when (reason) {
                        ERROR_CANNOT_RESUME -> Pair(
                            "ERROR_CANNOT_RESUME",
                            "Some possibly transient error occurred but we can't resume the download."
                        )
                        ERROR_DEVICE_NOT_FOUND -> Pair(
                            "ERROR_DEVICE_NOT_FOUND",
                            "No external storage device was found."
                        )
                        ERROR_FILE_ALREADY_EXISTS -> Pair(
                            "ERROR_FILE_ALREADY_EXISTS",
                            "The requested destination file already exists (the download manager will not overwrite an existing file)."
                        )
                        ERROR_FILE_ERROR -> Pair(
                            "ERROR_FILE_ERROR",
                            "A storage issue arises which doesn't fit under any other error code."
                        )
                        ERROR_HTTP_DATA_ERROR -> Pair(
                            "ERROR_HTTP_DATA_ERROR",
                            "An error receiving or processing data occurred at the HTTP level."
                        )
                        ERROR_INSUFFICIENT_SPACE -> Pair(
                            "ERROR_INSUFFICIENT_SPACE",
                            "There was insufficient storage space."
                        )
                        ERROR_TOO_MANY_REDIRECTS -> Pair("ERROR_TOO_MANY_REDIRECTS", "There were too many redirects.")
                        ERROR_UNHANDLED_HTTP_CODE -> Pair(
                            "ERROR_UNHANDLED_HTTP_CODE",
                            "An HTTP code was received that download manager can't handle."
                        )
                        ERROR_UNKNOWN -> Pair(
                            "ERROR_UNKNOWN",
                            "The download has completed with an error that doesn't fit under any other error code."
                        )
                        in 400..599 -> Pair(reason.toString(), "HTTP status code error.")
                        else -> Pair(reason.toString(), "Unknown.")
                    }
                    if (finished.compareAndSet(false, true)) {
                        onNext(DownloadStatus.Failed(requestResult, failedReason.first))
                        cancel()
                        onError(DownloadFailedException(failedReason.first, failedReason.second))
                    }
                }
                STATUS_PAUSED -> {
                    val pausedReason = when (reason) {
                        PAUSED_QUEUED_FOR_WIFI -> "PAUSED_QUEUED_FOR_WIFI"
                        PAUSED_UNKNOWN -> "PAUSED_UNKNOWN"
                        PAUSED_WAITING_FOR_NETWORK -> "PAUSED_WAITING_FOR_NETWORK"
                        PAUSED_WAITING_TO_RETRY -> "PAUSED_WAITING_TO_RETRY"
                        else -> "PAUSED_UNKNOWN"
                    }
                    onNext(DownloadStatus.Paused(requestResult, pausedReason))
                }
                STATUS_PENDING -> {
                    onNext(DownloadStatus.Pending(requestResult))
                }
                STATUS_SUCCESSFUL -> {
                    if (finished.compareAndSet(false, true)) {
                        onNext(DownloadStatus.Successful(requestResult))
                        unregisterReceiver()
                        onComplete()
                    }
                }
            }
        }
        cursor.close()
    }

    private fun createRequestResult(id: Long, cursor: Cursor): RequestResult =
        RequestResult(
            id = id,
            remoteUri = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_URI)),
            localUri = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LOCAL_URI)),
            mediaType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MEDIA_TYPE)),
            totalSize = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE_BYTES)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
            description = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION))
        )

    sealed class DownloadStatus(val result: RequestResult) {
        class Successful(result: RequestResult) : DownloadStatus(result)
        class Running(result: RequestResult, val progress: Int) : DownloadStatus(result)
        class Pending(result: RequestResult) : DownloadStatus(result)
        class Paused(result: RequestResult, val reason: String) : DownloadStatus(result)
        class Failed(result: RequestResult, val reason: String) : DownloadStatus(result)
    }

    class DownloadFailedException(val code: String, message: String) : Throwable(message)
}

data class RequestResult(
    val id: Long,
    val remoteUri: String,
    val localUri: String?,
    val mediaType: String?,
    val totalSize: Int,
    val title: String?,
    val description: String?
)