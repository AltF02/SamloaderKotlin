import androidx.compose.runtime.*
import com.soywiz.korio.async.async
import com.soywiz.korio.async.launch
import com.soywiz.korio.file.openAsync
import com.soywiz.korio.lang.format
import io.ktor.utils.io.core.internal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.readOnly
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.files.Blob
import org.w3c.xhr.BLOB
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import react.RBuilder
import react.dom.render
import react.dom.svg.Additive
import react.dom.unmountComponentAtNode
import tk.zwander.common.model.DecryptModel
import tk.zwander.common.model.DownloadModel
import tk.zwander.common.model.HistoryModel
import tk.zwander.common.res.Strings
import tk.zwander.common.tools.*
import tk.zwander.common.tools.Request
import tk.zwander.common.util.*
import kotlin.math.roundToInt

val downloadModel = DownloadModel()
val decryptModel = DecryptModel()
val historyModel = HistoryModel()

/**
 * @param key - when UseReactEffect is invoked with a new [key], compose forces react to render with a new content.
 * @param content - the builder for the content managed by React
 */
@Composable
private fun ElementScope<HTMLElement>.UseReactEffect(
    key: Any?,
    content: RBuilder.() -> Unit
) {
    DomSideEffect(key = key) { htmlElement ->
        render(htmlElement) {
            content()
        }
    }

    DisposableRefEffect { htmlElement ->
        onDispose {
            unmountComponentAtNode(htmlElement)
        }
    }
}

@OptIn(DangerousInternalIoApi::class)
val client = FusClient(useProxy = true)

fun main() {
    js("""
            window.originalFetch = window.fetch;
            window.fetch = function (resource, init) {
                init = Object.assign({}, init);
                init.credentials = init.credentials !== undefined ? init.credentials : 'include';
                return window.originalFetch(resource, init);
            };
        """)

    renderComposable(rootElementId = "root") {
        val xhr = remember { XMLHttpRequest() }

        Style(AppStyle)
        Div(
            attrs = {
                classes(AppStyle.main)
            }
        ) {
            Container(type = "fluid") {
                Row {
                    Column(xs = 12, md = 6) {
                        BootstrapTextInput(
                            value = downloadModel.model,
                        ) {
                            placeholder(Strings.modelHint)
                            onInput { downloadModel.model = it.value.uppercase() }
                        }
                    }

                    Column(xs = 12, md = 6) {
                        BootstrapTextInput(
                            value = downloadModel.region
                        ) {
                            placeholder(Strings.regionHint)
                            onInput { downloadModel.region = it.value.uppercase() }
                        }
                    }
                }

                Spacer(height = 1.em)

                Row {
                    Column {
                        BootstrapTextInput(downloadModel.fw) {
                            readOnly()
                            placeholder(Strings.firmware)
                            onInput { downloadModel.fw = it.value }
                        }
                    }
                }

                Spacer(height = 1.em)

                Row {
                    Column {
                        BootstrapButton(
                            attrs = {
                                onClick { downloadModel.job = downloadModel.scope.async { doCheck() } }
                            }
                        ) {
                            Text(Strings.retrieve)
                        }
                    }

                    if (downloadModel.fw.isNotBlank() && downloadModel.model.isNotBlank() && downloadModel.region.isNotBlank()
                        && downloadModel.job == null) {
                        Column {
                            BootstrapButton(
                                attrs = {
                                    onClick { downloadModel.job = downloadModel.scope.async { doDownload(xhr) } }
                                }
                            ) {
                                Text(Strings.download)
                            }
                        }
                    }

                    if (downloadModel.job != null) {
                        Column {
                            BootstrapButton(
                                attrs = {
                                    onClick {
                                        xhr.abort()
                                        downloadModel.endJob(Strings.canceled)
                                    }
                                }
                            ) {
                                Text(Strings.cancel)
                            }
                        }
                    }
                }

                Spacer(1.em)

                Row {
                    Text(Strings.statusFormat.format(downloadModel.statusText))
                }

                Spacer(1.em)

                Row {
                    val currentMB = (downloadModel.progress.first.toFloat() / 1024.0 / 1024.0 * 100.0).roundToInt() / 100.0
                    val totalMB = (downloadModel.progress.second.toFloat() / 1024.0 / 1024.0 * 100.0).roundToInt() / 100.0

                    val speedKBps = downloadModel.speed / 1024.0
                    val shouldUseMB = speedKBps >= 1 * 1024
                    val finalSpeed = "${((if (shouldUseMB) (speedKBps / 1024.0) else speedKBps) * 100.0).roundToInt() / 100.0}"

                    Column {
                        Text(Strings.mib.format(currentMB, totalMB))
                    }
                    Column {
                        Text("$finalSpeed ${if (shouldUseMB) Strings.mibs else Strings.kibs}")
                    }
                }
            }
        }
    }
}

suspend fun doCheck() {
    val (fw, os, error, output) = VersionFetch.getLatestVersion(downloadModel.model, downloadModel.region, true)

    if (error != null) {
        downloadModel.endJob(Strings.firmwareCheckError.format(error.message ?: "", output))
        return
    }

    downloadModel.changelog = ChangelogHandler.getChangelog(downloadModel.model, downloadModel.region, fw.split("/")[0], true)

    downloadModel.fw = fw
    downloadModel.osCode = os

    downloadModel.endJob("")
}

@OptIn(DangerousInternalIoApi::class, kotlin.time.ExperimentalTime::class)
suspend fun doDownload(xhr: XMLHttpRequest) {
    val (info, error, output) = Request.getBinaryFile(client, downloadModel.fw, downloadModel.model, downloadModel.region)

    if (error != null) {
        error.printStackTrace()
        downloadModel.endJob("${error.message ?: Strings.error}\n\n${output}")
    } else {
        val (path, fileName, size, crc32, v4Key) = info!!
        val request = Request.createBinaryInit(fileName, client.getNonce())

        client.makeReq(FusClient.Request.BINARY_INIT, request)

        val fullFileName = fileName.replace(
            ".zip",
            "_${downloadModel.fw.replace("/", "_")}_${downloadModel.region}.zip"
        )

        val averager = Averager()
        var prevSent = 0L
        var prevCallTime = Clock.System.now().toEpochMilliseconds()

        downloadModel.statusText = Strings.downloading

        val authV = client.getAuthV()
        val url = client.getDownloadUrl(path + fileName)

        xhr.open("GET", url)
        xhr.setRequestHeader("Authorization", authV)
        xhr.setRequestHeader("User-Agent", "Kies2.0_FUS")
        xhr.responseType = XMLHttpRequestResponseType.BLOB

        xhr.onload = { _ ->
            val blob = xhr.response as Blob
            val md5 = xhr.getResponseHeader("Content-MD5")

            downloadModel.speed = 0L

            downloadModel.scope.launch {
                if (crc32 != null) {
                    downloadModel.statusText = Strings.checkingCRC
                    val result = CryptUtils.checkCrc32(
                        blob.openAsync(),
                        size,
                        crc32
                    ) { current, max, bps ->
                        downloadModel.progress = current to max
                        downloadModel.speed = bps
                    }

                    if (!result) {
                        downloadModel.endJob(Strings.crcCheckFailed)
                        return@launch
                    }
                }

                if (md5 != null) {
                    downloadModel.statusText = Strings.checkingMD5
                    downloadModel.progress = 1L to 2L

                    val result = withContext(Dispatchers.Default) {
                        CryptUtils.checkMD5(
                            md5,
                            blob.openAsync()
                        )
                    }

                    if (!result) {
                        downloadModel.endJob(Strings.md5CheckFailed)
                    }
                }

                downloadModel.statusText = Strings.decrypting

                val key =
                    if (fullFileName.endsWith(".enc2")) CryptUtils.getV2Key(
                        downloadModel.fw,
                        downloadModel.model,
                        downloadModel.region
                    ) else {
                        v4Key ?: CryptUtils.getV4Key(client, downloadModel.fw, downloadModel.model, downloadModel.region)
                    }

                val decryptBuffer = createWriteStream(fullFileName.replace(".enc4", "").replace(".enc4", ""), size)

                CryptUtils.decryptProgress(
                    blob.openAsync(),
                    decryptBuffer.getWriter().openAsync { Uint8Array(it.toTypedArray()) },
                    key,
                    size
                ) { current, max, bps ->
                    downloadModel.progress = current to max
                    downloadModel.speed = bps
                }

                downloadModel.endJob(Strings.done)
            }

            Unit
        }

        xhr.onprogress = { progressEvent ->
            val newCallTime = Clock.System.now().toEpochMilliseconds()
            val nano = (newCallTime - prevCallTime) * 1000 * 1000

            averager.update(nano, progressEvent.loaded.toLong() - prevSent)

            val (totalTime, totalRead, _) = averager.sum()

            val bps = (totalRead / (totalTime.toDouble() / 1_000_000_000.0)).toLong()

            downloadModel.progress = progressEvent.loaded.toLong() to progressEvent.total.toLong()
            downloadModel.speed = bps

            prevCallTime = newCallTime
            prevSent = progressEvent.loaded.toLong()

            Unit
        }

        xhr.send()
    }
}
