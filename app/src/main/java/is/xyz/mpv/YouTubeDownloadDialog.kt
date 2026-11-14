package `is`.xyz.mpv

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import `is`.xyz.mpv.databinding.DialogYoutubeDownloadBinding

internal object YouTubeDownloadDialog {
    fun show(
        context: Context,
        owner: LifecycleOwner,
        download: YouTubeSaveClient.Download,
        onDismiss: (AlertDialog) -> Unit,
    ): AlertDialog {
        val binding = DialogYoutubeDownloadBinding.inflate(LayoutInflater.from(context))
        binding.downloadVideo.text = context.getString(
            R.string.youtube_download_video, download.url, download.format,
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.youtube_download_status)
            .setView(binding.root)
            .setPositiveButton(R.string.youtube_download_close, null)
            .create()

        val observer = Observer<YouTubeSaveClient.Status> { status ->
            render(context, binding, status)
        }
        dialog.setOnDismissListener {
            download.status.removeObserver(observer)
            onDismiss(dialog)
        }
        dialog.show()
        download.status.observe(owner, observer)
        return dialog
    }

    private fun render(
        context: Context,
        binding: DialogYoutubeDownloadBinding,
        status: YouTubeSaveClient.Status,
    ) {
        val heading = context.getString(stateLabel(status.state))
        binding.downloadStatus.text = buildList {
            add(heading)
            status.message?.let(::add)
            status.savedBytes?.let {
                add(context.getString(R.string.youtube_download_saved, Formatter.formatFileSize(context, it)))
            }
            status.elapsedSeconds?.let { seconds ->
                val time = DateUtils.formatElapsedTime(seconds.toLong())
                add(context.getString(R.string.youtube_download_elapsed, time))
            }
        }.joinToString("\n")

        renderStage(
            context, binding.audioLabel, binding.audioProgress, R.string.youtube_download_audio,
            status.stages[YouTubeSaveClient.Stage.AUDIO], status.state,
        )
        renderStage(
            context, binding.videoLabel, binding.videoProgress, R.string.youtube_download_video_stage,
            status.stages[YouTubeSaveClient.Stage.VIDEO], status.state,
        )
        renderStage(
            context, binding.mergeLabel, binding.mergeProgress, R.string.youtube_download_merge,
            status.stages[YouTubeSaveClient.Stage.MERGE], status.state,
        )
    }

    private fun renderStage(
        context: Context,
        label: TextView,
        bar: ProgressBar,
        titleRes: Int,
        stage: YouTubeSaveClient.StageProgress?,
        downloadState: YouTubeSaveClient.State,
    ) {
        val title = context.getString(titleRes)
        val finished = stage?.state == "done" || downloadState == YouTubeSaveClient.State.COMPLETE
        val percent = if (finished) 100 else stage?.percent
        val description = when {
            stage?.reused == true -> context.getString(R.string.youtube_download_reused)
            stage?.state == "failed" -> context.getString(R.string.youtube_download_failed)
            percent != null -> context.getString(R.string.youtube_download_percent, percent)
            downloadState.isFinal -> context.getString(R.string.youtube_download_no_progress)
            stage?.state == "running" -> context.getString(R.string.youtube_download_running)
            else -> context.getString(R.string.youtube_download_waiting)
        }
        label.text = buildList {
            add(context.getString(R.string.youtube_download_stage, title, description))
            if (stage != null && !stage.reused) {
                stage.bytes?.let { bytes ->
                    val downloaded = Formatter.formatFileSize(context, bytes)
                    val total = stage.totalBytes?.takeIf { it > 0 }
                    add(if (total == null) downloaded else context.getString(
                        R.string.youtube_download_bytes, downloaded, Formatter.formatFileSize(context, total),
                    ))
                }
                if (stage.state == "running" && !downloadState.isFinal) {
                    stage.bytesPerSecond?.takeIf { it > 0 }?.let { speed ->
                        add(context.getString(
                            R.string.youtube_download_speed, Formatter.formatFileSize(context, speed),
                        ))
                    }
                    stage.etaSeconds?.takeIf { it > 0 }?.let { eta ->
                        add(context.getString(R.string.youtube_download_eta, DateUtils.formatElapsedTime(eta)))
                    }
                }
            }
        }.joinToString(" · ")
        bar.isIndeterminate = stage?.state == "running" && percent == null && !downloadState.isFinal
        if (!bar.isIndeterminate) bar.progress = percent ?: 0
    }

    fun stateLabel(state: YouTubeSaveClient.State): Int = when (state) {
        YouTubeSaveClient.State.SENDING -> R.string.youtube_download_sending
        YouTubeSaveClient.State.RUNNING -> R.string.youtube_download_running
        YouTubeSaveClient.State.COMPLETE -> R.string.youtube_download_complete
        YouTubeSaveClient.State.FAILED -> R.string.youtube_download_failed
        YouTubeSaveClient.State.UNCONFIRMED -> R.string.youtube_download_unconfirmed
    }
}
