package com.logicianstudio.svg

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.android.kit.svg.SvgCompact

private const val FOLDER = "svg"
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        val svgList = assets.list(FOLDER)?.map { "$FOLDER/$it" } ?: arrayListOf()
        recyclerView.adapter = Adapter(this, svgList)
    }
}


class Adapter(
    private val context: Context,
    private val svgList: List<String>
) : RecyclerView.Adapter<Adapter.ViewHolder>() {


    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_svg, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        SvgCompact.loadAsset(context.assets, svgList[position]).into(holder.imageView)
    }

    override fun getItemCount() = svgList.size
}