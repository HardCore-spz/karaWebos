package com.example.androidremote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class VideoAdapter(
    private val onClick: (YouTubeVideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = mutableListOf<YouTubeVideoItem>()

    fun submitList(newItems: List<YouTubeVideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbImage: ImageView = itemView.findViewById(R.id.thumbImage)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val channelText: TextView = itemView.findViewById(R.id.channelText)

        fun bind(item: YouTubeVideoItem) {
            titleText.text = item.snippet.title
            channelText.text = item.snippet.channelTitle

            val thumbUrl = item.snippet.thumbnails.medium?.url
                ?: item.snippet.thumbnails.default?.url

            Glide.with(itemView)
                .load(thumbUrl)
                .centerCrop()
                .into(thumbImage)

            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
