package com.ko2ic.imagedownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.text.SimpleDateFormat
import java.util.*


class ImageDownloaderPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    companion object {
        private const val LOGGER_TAG = "image_downloader"
    }

    private lateinit var channel: MethodChannel
    private lateinit var activityBinding: ActivityPluginBinding
    private lateinit var permissionListener: ImageDownloaderPermissionListener

    private var inPublicDir: Boolean = true

    private var callback: CallbackImpl? = null

    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "plugins.ko2ic.com/image_downloader")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding

        permissionListener = ImageDownloaderPermissionListener(activityBinding.activity)
        activityBinding.addRequestPermissionsResultListener(permissionListener)
    }

    override fun onDetachedFromActivity() {
        activityBinding.removeRequestPermissionsResultListener(permissionListener)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "downloadImage" -> {
                inPublicDir = call.argument<Boolean>("inPublicDir") ?: true

                //println("PLUGIN ENTRYPOINT")

                if (!inPublicDir) {
                    result.notImplemented()
                    return
                }

                val permissionCallback = CallbackImpl(call, result, channel, activityBinding.activity)
                this.callback = permissionCallback

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    this.permissionListener.callback = permissionCallback
                    if (permissionListener.alreadyGranted()) {
                        permissionCallback.granted()
                    } else {
                        // The request permission will be triggered, which will be resolved in a callback
                    }
                } else {
                    // Since only public dirs are implemented, and above Q does not need any permissions
                    // this should act as granted
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
            //println("GRANTEDDDD!!!")
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

            // println("SAVE ${directory}  ${tempSubDirectory}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(directory, tempSubDirectory)
            } else {
                request.allowScanningByMediaScanner()
                request.setDestinationInExternalPublicDir(directory, tempSubDirectory)
            }


            val downloader = Downloader(context, request)
            this.downloader = downloader

            downloader.execute(
                    onNext = {
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
                            else -> {}
                        }

                    },
                    onError = {
                        result.error(it.code, it.message, null)
                    },
                    onComplete = {
                        result.success(null)
                    },
            )
        }

        override fun denied() {
            result.error("no_permission", "Write permission denied", null)
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