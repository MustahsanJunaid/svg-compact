package com.logicianstudio.svg

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.kit.file.createDirectoriesIfNeeded
import com.android.kit.svg.SvgCompact
import com.android.kit.ui.activity.KitActivity
import com.logicianstudio.svg.databinding.ActivityListSvgBinding
import java.io.File

class SvgListActivity : KitActivity<ActivityListSvgBinding>() {

    override fun onCreateBinding() = ActivityListSvgBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermission(arrayOf(WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE)) {
            (
                Environment.getExternalStorageDirectory()
                    .toString() + File.separator + getString(R.string.app_name)
                )
                .createDirectoriesIfNeeded()?.let { directory ->
                    binding.recyclerView.adapter =
                        Adapter(this, directory.listFiles()?.toList() ?: listOf())
                }
        }
    }
}

class Adapter(
    private val context: Context,
    private val svgList: List<File>
) : RecyclerView.Adapter<Adapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val textView: TextView = view.findViewById(R.id.fileNameTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_svg, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = svgList[position]
        val path = file.path
        val filename = path.substring(path.lastIndexOf("/") + 1).replace(".svg", "")
        holder.textView.text = filename
        SvgCompact.loadFile(file).into(holder.imageView)
    }

    override fun getItemCount() = svgList.size
}
