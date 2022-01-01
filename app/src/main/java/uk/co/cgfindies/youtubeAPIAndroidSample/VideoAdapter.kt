package uk.co.cgfindies.youtubeAPIAndroidSample

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.api.services.youtube.model.PlaylistItem
import com.google.api.services.youtube.model.Thumbnail
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.URL


class VideoAdapter(context: Context, private val values: MutableList<PlaylistItem>) : ArrayAdapter<PlaylistItem>(context, -1, values), CoroutineScope by MainScope() {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val video = values[position]
        var description = video.snippet.description
        description = if (description.length <= 120) description else description.substring(0, 117) + "..."
        val thumbnail = video.snippet.thumbnails.default

        return if (convertView != null) {
            convertView.findViewById<TextView>(R.id.video_name).text = video.snippet.title
            convertView.findViewById<TextView>(R.id.video_status).text = context.resources.getString(R.string.listview_item_video_status, video.status.privacyStatus)
            convertView.findViewById<TextView>(R.id.video_description).text = description
            val imageView = convertView.findViewById<ImageView>(R.id.video_thumbnail)
            getImage(thumbnail, imageView)
            convertView
        } else {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val row = inflater.inflate(R.layout.listview_item_video, parent, false) as LinearLayout

            row.findViewById<TextView>(R.id.video_name).text = video.snippet.title
            row.findViewById<TextView>(R.id.video_status).text = context.resources.getString(R.string.listview_item_video_status, video.status.privacyStatus)
            row.findViewById<TextView>(R.id.video_description).text = description
            val imageView = row.findViewById<ImageView>(R.id.video_thumbnail)
            getImage(thumbnail, imageView)
            row
        }
    }

    private fun getImage(thumbnail: Thumbnail, view: ImageView) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                try {
                    val imageStream: InputStream = URL(thumbnail.url).openStream()
                    val image = BitmapFactory.decodeStream(imageStream)
                    val layoutParams =
                        LinearLayout.LayoutParams(thumbnail.width.toInt(), thumbnail.height.toInt())
                    withContext(Dispatchers.Main) {
                        view.setImageBitmap(image)
                        view.layoutParams = layoutParams
                        view.requestLayout()
                    }
                } catch (e: Exception) {
                    Log.e("VIDEO_DISPLAY", "Could not download image", e)
                    e.printStackTrace()
                }
            }
        }
    }
}
