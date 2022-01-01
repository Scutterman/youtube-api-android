package uk.co.cgfindies.youtubeAPIAndroidSample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.*
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Channel
import com.google.api.services.youtube.model.PlaylistItem
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@DelicateCoroutinesApi
class MainActivity : AppCompatActivity(), DefaultLifecycleObserver {
    private var tokenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        tokenId = savedInstanceState?.getString("tokenId")
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        setContentView(R.layout.activity_main)

        val retry = findViewById<Button>(R.id.auth_retry)
        retry.setOnClickListener {
            doAuth()
        }

        val upload = findViewById<Button>(R.id.btn_upload_video)
        upload.setOnClickListener {
            upload.isEnabled = false
            showChannels()
            upload.isEnabled = true
        }

        val resetCredentials = findViewById<Button>(R.id.btn_reset_credentials)
        resetCredentials.setOnClickListener {
            Utility.resetCredentials(this)
            showAuth()
        }

        Utility.getAuthentication(this) { auth ->
            if (auth == null) {
                showAuth()
                doAuth()
            } else {
                showResults()
                showChannels()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tokenId", tokenId)
        super.onSaveInstanceState(outState)
    }

    override fun onStart(owner: LifecycleOwner) {
        // app moved to foreground
        if (tokenId == null) {
            return
        }
        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(
            Request.Method.GET, "$url/getStoredToken?tokenId=$tokenId", null,
            { response ->
                val data = response?.toString() ?: return@JsonObjectRequest
                try {
                    val auth = Json.decodeFromString<AccessTokenResponse>(data)
                    Utility.setAuthentication(auth, this)
                    tokenId = null
                    showResults()
                } catch (_error: Exception) {
                    Log.e("FETCH_TOKEN", "Error while decoding the token", _error)
                    showError()
                }
            },
            { error ->
                Log.e("FETCH_TOKEN", "Could not get auth url", error)
                Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(this)
        queue.add(authRequest)
    }

    override fun onStop(owner: LifecycleOwner) {
        // app moved to background, no action required
    }

    private fun showAuth() {
        findViewById<View>(R.id.not_a_token).visibility = View.GONE
        findViewById<View>(R.id.results).visibility = View.GONE
        findViewById<View>(R.id.auth).visibility = View.VISIBLE
    }

    private fun showResults() {
        findViewById<View>(R.id.auth).visibility = View.GONE
        findViewById<View>(R.id.results).visibility = View.VISIBLE
    }

    private fun showError() {
        findViewById<View>(R.id.not_a_token).visibility = View.VISIBLE
    }

    private fun setOutputText(text: String) {
        this.findViewById<TextView>(R.id.upload_output).text = text
    }

    private fun populateVideoList(videos: MutableList<PlaylistItem>) {
        val list = findViewById<ListView>(R.id.video_list)
        val videoAdapter = VideoAdapter(this, videos)
        list.adapter = videoAdapter
        Log.i("RESULTS", "Done populating video list")
    }

    private fun doAuth() {
        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(Request.Method.GET, "$url/generateAuthUrl", null,
            { response ->
                val authUrl = response?.getString("url")
                tokenId = response?.getString("tokenId")
                if (authUrl == null || tokenId == null) {
                    Log.e("FETCH_AUTH_URL", "Auth URL Response was not in the correct format ${ response?.toString() ?: "NULL" }")
                    Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                    startActivity(browserIntent)
                }
            },
            { error ->
                Log.e("FETCH_AUTH_URL", "Could not get auth url", error)
                Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(this)
        queue.add(authRequest)
    }

    private fun showChannels() {
        Log.i("UPLOAD", "Showing channels")
        setOutputText("")

        getChannelList { channels ->
            if (channels == null || channels.isEmpty()) {
                Log.i("UPLOAD", "No results")
                Utility.showMessage(this, R.string.no_results)
            } else {
                Log.i("UPLOAD", "Got data")
                val channelSummary = channels.map { channel ->
                    resources.getString(R.string.upload_channel_description, channel.snippet.title, channel.statistics.viewCount)
                }


                setOutputText("Data retrieved using the YouTube Data API:\n\n" + TextUtils.join("\n\n", channelSummary))

                val playlistId = "UU" + channels[0].id.substring(2)
                getPlaylistVideos(playlistId)
            }
        }
    }

    private fun getChannelList(onResult: (List<Channel>?) -> Unit) {
        Log.i("UPLOAD", "Performing channel list action")
        MakeRequestTask<List<Channel>>({ apiClient ->
            Log.i("UPLOAD", "data from api")
            val result = apiClient.channels().list("snippet,contentDetails,statistics")
                .setMine(true)
                .execute()

            Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
            return@MakeRequestTask result.items?.toList() ?: emptyList()
        }, onResult).execute()
        Log.i("UPLOAD", "Executed channel list item")
    }

    private fun getPlaylistVideos(playlistId: String) {
        Log.i("UPLOAD", "Performing video list action")
        MakeRequestTask<List<PlaylistItem>>({ apiClient ->
            Log.i("UPLOAD", "data from api")
            val result = apiClient.playlistItems().list("snippet,contentDetails,status")
                .setPlaylistId(playlistId)
                .setMaxResults(10)
                .execute()

            Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
            return@MakeRequestTask result.items?.toList() ?: emptyList()
        }, { items ->
            if (items == null || items.isEmpty()) {
                Log.i("VIDEOS", "No results")
                Utility.showMessage(this, R.string.no_results)
            } else {
                Log.i("VIDEOS", "Got data")
                populateVideoList(items.toMutableList())
            }
        }).execute()
        Log.i("UPLOAD", "Executed video list action")
    }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private inner class MakeRequestTask<Result>
    constructor(private val fetchData: (apiClient: YouTube) -> Result, private val onResult: (result: Result?) -> Unit) :
        CoroutinesAsyncTask<Void?, Void?, Result>("MakeYoutubeRequest"),
        HttpRequestInitializer {

        var auth: AccessTokenResponse? = null

        /**
         * Background task to call YouTube Data API.
         * @param params no parameters needed for this task.
         */
        override fun doInBackground(vararg params: Void?): Result? {
            return try {
                Log.i("UPLOAD", "Setting up the api client")
                val transport = AndroidHttp.newCompatibleTransport()
                val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
                val apiClient = YouTube.Builder(transport, jsonFactory, this)
                    .setApplicationName("YouTube API Android Sample")
                    .build()
                Log.i("UPLOAD", "Trying to get the data")
                fetchData(apiClient)
            } catch (e: Exception) {
                Log.e("UPLOAD", "do background error", e)
                cancel(true, e)
                null
            }
        }

        override suspend fun onPreExecute(): Unit = suspendCoroutine { cont ->
            Log.i("UPLOAD", "Pre execute")
            Utility.getAuthentication(this@MainActivity) { auth ->
                Log.i("UPLOAD", "Fetched auth")
                this.auth = auth
                Log.i("UPLOAD", "Resuming task flow")
                cont.resume(Unit)
            }
        }

        override fun onPostExecute(result: Result?) {
            Log.i("UPLOAD", "Post execute")
            onResult(result)
        }

        override fun onCancelled(e: java.lang.Exception?) {
            Log.i("UPLOAD", "Cancelled")
            if (e != null) {
                Log.e("UPLOAD", "Error while uploading", e)
                Utility.showMessage(this@MainActivity, R.string.error_uploading)
            } else {
                Utility.showMessage(this@MainActivity, R.string.upload_cancelled)
            }
        }

        override fun initialize(request: HttpRequest?) {
            Log.i("UPLOAD", "Initializing the request")
            if (request == null) {
                Log.i("UPLOAD", "No request, nothing to do")
                return
            }

            Log.i("UPLOAD", "Checking the auth")
            val auth = this.auth ?: throw Exception("Set authentication before running a request")

            Log.i("UPLOAD", "Setting request handler")
            val handler = RequestHandler(auth)
            request.interceptor = handler
            request.unsuccessfulResponseHandler = handler
            Log.i("UPLOAD", "Initialize complete")
        }
    }

    private inner class RequestHandler (val auth: AccessTokenResponse) : HttpExecuteInterceptor,
        HttpUnsuccessfulResponseHandler {

        override fun intercept(request: HttpRequest) {
            Log.i("UPLOAD", "Intercepted the request, adding an access token")
            request.headers.authorization = "Bearer ${ auth.access_token }"
        }

        override fun handleResponse(
            request: HttpRequest, response: HttpResponse, supportsRetry: Boolean
        ): Boolean {
            Log.i("UPLOAD", "Request failed with the status" + response.statusCode.toString())
            // If the response was 401, the problem should be solved by the user retrying
            // Otherwise, we can't fix it anyway
            return false
        }
    }
}
