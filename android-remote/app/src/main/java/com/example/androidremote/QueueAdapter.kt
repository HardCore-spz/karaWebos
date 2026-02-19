package com.example.androidremote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class QueueAdapter(
    private val onRemove: (Int) -> Unit,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<YouTubeVideoItem>()
    private var playingIndex = -1

    fun submitList(newItems: List<YouTubeVideoItem>, currentPlaying: Int) {
        items.clear()
        items.addAll(newItems)
        playingIndex = currentPlaying
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playingIndicator: View = itemView.findViewById(R.id.playingIndicator)
        private val numberText: TextView = itemView.findViewById(R.id.queueNumberText)
        private val thumbImage: ImageView = itemView.findViewById(R.id.thumbImage)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val channelText: TextView = itemView.findViewById(R.id.channelText)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)
        private val rootLayout: View = itemView.findViewById(R.id.queueItemRoot)

        fun bind(item: YouTubeVideoItem, position: Int) {
            val isCurrentPlaying = position == playingIndex

            // Playing indicator
            playingIndicator.visibility = if (isCurrentPlaying) View.VISIBLE else View.GONE

            // Number
            numberText.text = (position + 1).toString()
            if (isCurrentPlaying) {
                numberText.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            } else {
                numberText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
            }

            // Title
            titleText.text = item.snippet.title
            if (isCurrentPlaying) {
                titleText.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            } else {
                titleText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
            }

            // Channel
            channelText.text = item.snippet.channelTitle

            // Background for current playing
            if (isCurrentPlaying) {
                rootLayout.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.playing_bg))
            } else {
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val typedArray = itemView.context.obtainStyledAttributes(attrs)
                val bg = typedArray.getDrawable(0)
                typedArray.recycle()
                rootLayout.background = bg
            }

            // Thumbnail
            val thumbUrl = item.snippet.thumbnails.medium?.url
                ?: item.snippet.thumbnails.default?.url

            Glide.with(itemView)
                .load(thumbUrl)
                .centerCrop()
                .into(thumbImage)

            // Remove button
            btnRemove.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemove(pos)
                }
            }

            // Click to play
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onClick(pos)
                }
            }
        }
    }
}
