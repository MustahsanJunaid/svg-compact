package com.logicianstudio.svg

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract.Data
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.kit.file.createDirectoriesIfNeeded
import com.android.kit.logD
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
                        Adapter(
                            this,
                            directory.listFiles()?.toList()?.map { SvgData(file = it) }
                                ?.sortedByDescending {
                                    it.file.name.replace(".svg", "").trim().toInt()
                                } ?: listOf())
                }
        }
    }
}

class Adapter(
    private val context: Context,
    private val svgList: List<SvgData>,
) : RecyclerView.Adapter<Adapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val textView: TextView = view.findViewById(R.id.fileNameTextView)
        val colorToggleText: TextView = view.findViewById(R.id.colorToggleTextView)
        val cardView: CardView = view.findViewById(R.id.cardView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_svg, parent, false),
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = svgList[position]
        val path = item.file.path
        val filename = path.substring(path.lastIndexOf("/") + 1).replace(".svg", "")
        holder.textView.text = filename
        if(item.isDark)
        holder.colorToggleText.text = if(item.isDark) "W" else "B"
        holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, if(item.isDark) R.color.cardview_dark_background else R.color.cardview_light_background))
        SvgCompact.loadFile(item.file).into(holder.imageView)
        holder.colorToggleText.setOnClickListener {
            item.isDark != item.isDark
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = svgList.size
}
