package dev.brahmkshatriya.echo.ui.player.more.upnext

import android.annotation.SuppressLint
import android.graphics.drawable.Animatable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemPlaylistTrackBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.addedByName
import dev.brahmkshatriya.echo.playback.MediaItemUtils.addedByAvatar
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.subtitle
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.applyTranslationYAnimation
import dev.brahmkshatriya.echo.utils.ui.UiUtils.marquee
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class QueueAdapter(
    private val listener: Listener,
    private val inactive: Boolean = false
) : ListAdapter<Pair<Boolean?, MediaItem>, QueueAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<Pair<Boolean?, MediaItem>>() {
        override fun areItemsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem.second.mediaId == newItem.second.mediaId

        override fun areContentsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem == newItem
    }

    open class Listener {
        open fun onItemClicked(position: Int) {}
        open fun onItemClosedClicked(position: Int) {}
        open fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(
        val binding: ItemPlaylistTrackBinding
    ) : ScrollAnimViewHolder(binding.root) {

        init {
            binding.playlistItemClose.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClosedClicked(pos)
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClicked(pos)
            }

            binding.playlistItemDrag.setOnTouchListener { _, event ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnTouchListener false
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                listener.onDragHandleTouched(this)
                true                                                                                                                                                       
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemPlaylistTrackBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.onBind(position)
        holder.itemView.applyTranslationYAnimation(scrollAmount)
    }

    private fun ViewHolder.onBind(position: Int) {
        val (current, item) = getItem(position)
        val isCurrent = current != null
        val isPlaying = current == true
        val track = item.track
        val addedBy = item.addedByName
        val avatarUrl = item.addedByAvatar
        
        binding.bind(track, addedBy, avatarUrl)
        binding.isPlaying(isPlaying)
        binding.playlistItemClose.isVisible = !inactive
        binding.playlistItemDrag.isVisible = !inactive
        binding.playlistCurrentItem.isVisible = isCurrent
        binding.playlistProgressBar.isVisible = isCurrent && !item.isLoaded
        binding.playlistItem.alpha = if (inactive) 0.5f else 1f
    }

    private var scrollAmount: Int = 0
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            scrollAmount = dy
        }
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        this.recyclerView = null
    }

    companion object {
        fun ItemPlaylistTrackBinding.bind(track: Track, addedBy: String? = null, avatarUrl: String? = null) {
            playlistItemTitle.run {
                text = track.title
                marquee()
            }

            track.cover.loadInto(playlistItemImageView, R.drawable.art_music)
            
            playlistItemAuthor.run {
                val artistSubtitle = track.subtitle(root.context)
                isVisible = !artistSubtitle.isNullOrEmpty()
                text = artistSubtitle
                marquee()
            }

            val container = playlistItemAuthor.parent as? LinearLayout
            if (container != null) {
                var addedByContainer = container.findViewWithTag<LinearLayout>("added_by_container")
                
                if (addedByContainer == null && !addedBy.isNullOrEmpty()) {
                    addedByContainer = LinearLayout(root.context).apply {
                        tag = "added_by_container"
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = 4 
                        }

                        val avatarView = ImageView(root.context).apply {
                            tag = "added_by_avatar"
                            layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                                rightMargin = 12
                            }
                        }
                        addView(avatarView)

                        val nameView = TextView(root.context).apply {
                            tag = "added_by_name"
                            textSize = 10.5f
                            setTextColor(playlistItemAuthor.currentTextColor)
                            alpha = 0.6f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        addView(nameView)
                    }
                    val index = container.indexOfChild(playlistItemAuthor) + 1
                    container.addView(addedByContainer, index)
                }

                addedByContainer?.run {
                    isVisible = !addedBy.isNullOrEmpty()
                    if (isVisible) {
                        val avatarView = findViewWithTag<ImageView>("added_by_avatar")
                        val nameView = findViewWithTag<TextView>("added_by_name")
                        nameView?.text = "Added by $addedBy"
                        
                        val finalAvatar = avatarUrl ?: "https://api.dicebear.com/7.x/identicon/png?seed=$addedBy"
                        avatarView?.load(finalAvatar) {
                            transformations(CircleCropTransformation())
                            crossfade(true)
                            error(R.drawable.art_music)
                        }
                    }
                }
            }
        }

        fun ItemPlaylistTrackBinding.isPlaying(isPlaying: Boolean) {
            playlistItemNowPlaying.isVisible = isPlaying
            (playlistItemNowPlaying.drawable as Animatable).start()
        }
    }
}
