package `is`.xyz.mpv

import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

internal object YouTubeSaveClient {
    const val DEFAULT_FORMAT = "251+399"
    private val executor = Executors.newCachedThreadPool()
    private val downloads = mutableListOf<Download>()

    enum class State(val isFinal: Boolean = false) {
        SENDING, RUNNING, COMPLETE(true), FAILED(true), UNCONFIRMED(true)
    }

    enum class Stage { AUDIO, VIDEO, MERGE }

    data class StageProgress(
        val state: String,
        val percent: Int?,
        val reused: Boolean,
        val bytes: Long?,
        val totalBytes: Long?,
        val bytesPerSecond: Long?,
        val etaSeconds: Long?,
    )

    data class Status(
        val state: State = State.SENDING,
        val message: String? = null,
        val stages: Map<Stage, StageProgress> = emptyMap(),
        val elapsedSeconds: Double? = null,
        val savedBytes: Long? = null,
    )

    class Download(val url: String, val format: String) {
        private val updates = MutableLiveData(Status())
        val status: LiveData<Status> = updates

        internal fun publish(status: Status) {
            updates.postValue(status)
        }
    }

    @MainThread
    fun recentDownloads(): List<Download> = downloads.toList()

    @MainThread
    fun submit(url: String, format: String): Download {
        val download = Download(url, format)
        downloads.add(0, download)
        val expired = downloads.filter { it.status.value?.state?.isFinal == true }
            .drop(COMPLETED_HISTORY_LIMIT)
        downloads.removeAll(expired.toSet())
        executor.execute {
            send(download)
        }
        return download
    }

    private fun send(download: Download) {
        val payload = JSONObject()
            .put("url", download.url)
            .put("resolution", download.format)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val connection = URL("http://127.0.0.1:7010/save")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        var status = Status()
        var sent = false
        fun publish(update: Status) {
            status = update
            download.publish(update)
        }

        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 0
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/x-ndjson")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            sent = true

            val code = connection.responseCode
            if (code !in 200..299) {
                publish(status.copy(state = State.FAILED, message = readHttpError(connection)))
                return
            }
            publish(status.copy(state = State.RUNNING))
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                for (line in reader.lineSequence()) {
                    if (line.isBlank()) continue
                    val event = JSONObject(line)
                    updateFromEvent(status, event)?.let(::publish)
                    if (status.state.isFinal) return
                }
            }
            publish(status.copy(state = State.UNCONFIRMED, message = null))
        } catch (exception: IOException) {
            Log.w(TAG, "YouTube save connection closed with an error", exception)
            publish(status.copy(state = if (sent) State.UNCONFIRMED else State.FAILED, message = null))
        } catch (exception: JSONException) {
            Log.w(TAG, "Invalid YouTube save response", exception)
            publish(status.copy(state = State.UNCONFIRMED, message = null))
        } finally {
            connection.disconnect()
        }
    }

    private fun readHttpError(connection: HttpURLConnection): String? =
        runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val line = reader.readLine() ?: return@use null
                JSONObject(line).text("error")
            }
        }.getOrNull()

    internal fun updateFromEvent(status: Status, event: JSONObject): Status? {
        val type = event.optString("type")
        if (type == "result" || event.has("success")) {
            val success = event.optBoolean("success")
            return status.copy(
                state = if (success) State.COMPLETE else State.FAILED,
                message = event.text("error"),
                elapsedSeconds = event.optDouble("elapsed").takeIf { it.isFinite() && it >= 0 },
                savedBytes = event.optLong("saved_bytes", -1).takeIf { it >= 0 },
            )
        }

        return when (type) {
            "status" -> status.copy(message = event.text("message"))
            "progress" -> {
                val stage = when (event.optString("stage")) {
                    "audio" -> Stage.AUDIO
                    "video" -> Stage.VIDEO
                    "merge" -> Stage.MERGE
                    else -> return null
                }
                val progress = StageProgress(
                    state = event.optString("state", "waiting"),
                    percent = event.optDouble("percent").takeIf { it.isFinite() }
                        ?.coerceIn(0.0, 100.0)?.toInt(),
                    reused = event.optBoolean("reused"),
                    bytes = event.nonNegativeLong("bytes"),
                    totalBytes = event.nonNegativeLong("total"),
                    bytesPerSecond = event.nonNegativeLong("speed"),
                    etaSeconds = event.nonNegativeLong("eta"),
                )
                status.copy(stages = status.stages + (stage to progress))
            }
            else -> null
        }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        optLong(key, -1).takeIf { it >= 0 }

    private const val COMPLETED_HISTORY_LIMIT = 15
    private const val TAG = "YouTubeSaveClient"
}
