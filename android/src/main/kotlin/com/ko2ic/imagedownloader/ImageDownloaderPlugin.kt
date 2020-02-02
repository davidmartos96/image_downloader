package com.ko2ic.imagedownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.text.SimpleDateFormat
import java.util.*


class ImageDownloaderPlugin(
        private val registrar: Registrar,
        private val channel: MethodChannel,
        private val permissionListener: ImageDownloaderPermissionListener
) : MethodCallHandler {
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "plugins.ko2ic.com/image_downloader")
            val activity = registrar.activity() ?: return

            val listener = ImageDownloaderPermissionListener(activity)
            registrar.addRequestPermissionsResultListener(listener)
            channel.setMethodCallHandler(ImageDownloaderPlugin(registrar, channel, listener))
        }

        private const val LOGGER_TAG = "image_downloader"
    }

    private var inPublicDir: Boolean = true

    private var callback: CallbackImpl? = null

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "downloadImage" -> {
                inPublicDir = call.argument<Boolean>("inPublicDir") ?: true

                val permissionCallback = CallbackImpl(call, result, channel, registrar.context())
                this.callback = permissionCallback
                if (inPublicDir) {
                    this.permissionListener.callback = permissionCallback
                    if (permissionListener.alreadyGranted()) {
                        permissionCallback.granted()
                    }
                } else {
                    permissionCallback.granted()
                }
            }
            else -> result.notImplemented()
        }
    }

    class CallbackImpl(
            private val call: MethodCall,
            private val result: Result,
            private val channel: MethodChannel,
            private val context: Context
    ) :
            ImageDownloaderPermissionListener.Callback {

        var downloader: Downloader? = null

        override fun granted() {
            val url = call.argument<String>("url")
                    ?: throw IllegalArgumentException("url is required.")

            val headers: Map<String, String>? = call.argument<Map<String, String>>("headers")

            val directoryType = call.argument<String>("directory") ?: "DIRECTORY_DOWNLOADS"
            val subDirectory = call.argument<String>("subDirectory")
            val tempSubDirectory = subDirectory ?: SimpleDateFormat(
                    "yyyy-MM-dd.HH.mm.sss",
                    Locale.getDefault()
            ).format(Date())

            val directory = convertToDirectory(directoryType)

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)

            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            if (headers != null) {
                for ((key, value) in headers) {
                    request.addRequestHeader(key, value)
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "$directory/$tempSubDirectory")
            } else {
                request.allowScanningByMediaScanner()
                request.setDestinationInExternalPublicDir(directory, tempSubDirectory)
            }


            val downloader = Downloader(context, request)
            this.downloader = downloader

            downloader.execute(onNext = {
                Log.d(LOGGER_TAG, it.result.toString())
                when (it) {
                    is Downloader.DownloadStatus.Failed -> Log.d(LOGGER_TAG, it.reason)
                    is Downloader.DownloadStatus.Paused -> Log.d(LOGGER_TAG, it.reason)
                    is Downloader.DownloadStatus.Running -> {
                        Log.d(LOGGER_TAG, it.progress.toString())
                        val args = HashMap<String, Any>()
                        args["image_id"] = it.result.id.toString()
                        args["progress"] = it.progress

                        val uiThreadHandler = Handler(Looper.getMainLooper())

                        uiThreadHandler.post {
                            channel.invokeMethod("onProgressUpdate", args)
                        }
                    }
                }

            }, onError = {
                result.error(it.code, it.message, null)
            }, onComplete = {
                result.success(null)
            })
        }

        override fun denied() {
            result.success(null)
        }

        private fun convertToDirectory(directoryType: String): String {
            return when (directoryType) {
                "DIRECTORY_DOWNLOADS" -> Environment.DIRECTORY_DOWNLOADS
                "DIRECTORY_PICTURES" -> Environment.DIRECTORY_PICTURES
                "DIRECTORY_DCIM" -> Environment.DIRECTORY_DCIM
                "DIRECTORY_MOVIES" -> Environment.DIRECTORY_MOVIES
                else -> directoryType
            }
        }
    }
}