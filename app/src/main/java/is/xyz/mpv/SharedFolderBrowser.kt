package `is`.xyz.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executor

internal class SharedFolderBrowser(
    context: Context,
    private val volumes: List<SharedStorageFolders.Volume>,
    initialFolder: File?,
    private val executor: Executor,
    onSelected: (File) -> Unit,
) {
    private val view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null)
    private val search = view.findViewById<EditText>(R.id.folderSearchInput)
    private val list = view.findViewById<ListView>(R.id.folderListView)
    private val breadcrumb = view.findViewById<TextView>(R.id.folderBreadcrumb)
    private val empty = view.findViewById<TextView>(R.id.folderEmptyText)
    private val up = view.findViewById<View>(R.id.folderUpButton)
    private val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
    private val handler = Handler(Looper.getMainLooper())
    private var folder = initialFolder ?: volumes.singleOrNull()?.root
    private var rows = emptyList<File>()
    private var visibleRows = emptyList<File>()
    private var loadGeneration = 0
    private var loading = false
    private var readable = false

    val dialog: AlertDialog =
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.choose_folder)
            .setView(view)
            .setPositiveButton(R.string.use_this_folder, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    init {
        list.adapter = adapter
        list.emptyView = empty
        search.doAfterTextChanged { renderRows() }
        up.setOnClickListener {
            val current = folder ?: return@setOnClickListener
            val volume = volumes.firstOrNull { SharedStorageFolders.contains(it.root, current) }
            navigate(if (current == volume?.root) null else current.parentFile)
        }
        list.setOnItemClickListener { _, _, index, _ ->
            visibleRows.getOrNull(index)?.let(::navigate)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = folder ?: return@setOnClickListener
                if (readable && SharedStorageFolders.hasAllFilesAccess()) {
                    dialog.dismiss()
                    onSelected(selected)
                } else {
                    readable = false
                    renderRows()
                }
            }
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            navigate(folder)
        }
    }

    private fun navigate(destination: File?) {
        folder = destination
        val generation = ++loadGeneration
        readable = false
        loading = destination != null
        rows = if (destination == null) volumes.map { it.root } else emptyList()
        search.text?.clear()
        renderRows()
        if (destination == null) return

        executor.execute {
            val children =
                SharedStorageFolders.readableEntries(destination)
                    ?.filter(File::isDirectory)
                    ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            handler.post {
                if (!dialog.isShowing || generation != loadGeneration) return@post
                loading = false
                readable = children != null
                rows = children.orEmpty()
                renderRows()
            }
        }
    }

    private fun renderRows() {
        if (!dialog.isShowing) return
        val current = folder
        val volume =
            current?.let { directory ->
                volumes.firstOrNull { SharedStorageFolders.contains(it.root, directory) }
            }
        val relative =
            if (current != null && volume != null) {
                current.relativeTo(volume.root).invariantSeparatorsPath
            } else ""
        breadcrumb.text =
            when {
                current == null -> view.context.getString(R.string.shared_storage_label)
                volume == null -> current.path
                relative.isEmpty() -> volume.label
                else -> "${volume.label} / $relative"
            }
        up.isEnabled = current != null && (current != volume?.root || volumes.size > 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = current != null && readable
        val query = search.text.toString().trim()
        fun label(file: File): String =
            if (current != null) file.name
            else volumes.firstOrNull { it.root == file }?.label ?: file.name
        visibleRows = rows.filter { label(it).contains(query, ignoreCase = true) }
        empty.setText(
            when {
                loading -> R.string.scanning_media
                current != null && !readable -> R.string.folder_access_error
                query.isNotEmpty() -> R.string.no_matching_folders
                else -> R.string.no_shared_subfolders
            }
        )
        adapter.clear()
        adapter.addAll(visibleRows.map(::label))
    }
}
