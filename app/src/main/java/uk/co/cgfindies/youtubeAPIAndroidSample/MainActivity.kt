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

        // This is important for when the user is returning from the OAuth flow
        tokenId = savedInstanceState?.getString("tokenId")
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Set up the view
        setContentView(R.layout.activity_main)

        /** Button Listeners **/
        val retry = findViewById<Button>(R.id.auth_retry)
        retry.setOnClickListener {
            doAuth()
        }

        val list = findViewById<Button>(R.id.btn_list_videos)
        list.setOnClickListener {
            list.isEnabled = false
            showChannels()
            list.isEnabled = true
        }

        val resetCredentials = findViewById<Button>(R.id.btn_reset_credentials)
        resetCredentials.setOnClickListener {
            Utility.resetCredentials(this)
            showAuth()
        }
        /** End of button listeners **/

        // When the user first arrives in the app, they won't have OAuth credentials
        // In that case, we want to trigger the OAuth Flow immediately
        // Otherwise, we want to show them the results
        Utility.getAuthentication(this) { auth ->
            if (auth == null) {
                showAuth()
            } else {
                showResults()
            }
        }
    }

    // Store the tokenId so we can access it in the bundle in `onCreate`
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tokenId", tokenId)
        super.onSaveInstanceState(outState)
    }

    // When the app returns to the foreground after the OAuth Flow, request the credentials from the API Server
    override fun onStart(owner: LifecycleOwner) {
        Log.i("onStart", "Resuming: $tokenId")
        if (tokenId == null) {
            Log.i("onStart", "No token id, nothing to do")
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

    /** Helper functions to show / hide parts of the UI **/
    private fun showAuth() {
        findViewById<View>(R.id.not_a_token).visibility = View.GONE
        findViewById<View>(R.id.results).visibility = View.GONE
        findViewById<View>(R.id.auth).visibility = View.VISIBLE
        doAuth()
    }

    private fun showResults() {
        findViewById<View>(R.id.auth).visibility = View.GONE
        findViewById<View>(R.id.results).visibility = View.VISIBLE
        showChannels()
    }

    private fun showError() {
        findViewById<View>(R.id.not_a_token).visibility = View.VISIBLE
    }

    private fun setOutputText(text: String) {
        this.findViewById<TextView>(R.id.api_output).text = text
    }

    /** End helper functions **/

    // Set up an Adapter and hook it up to the video ListView
    private fun populateVideoList(videos: MutableList<PlaylistItem>) {
        val list = findViewById<ListView>(R.id.video_list)
        val videoAdapter = VideoAdapter(this, videos)
        list.adapter = videoAdapter
        Log.i("RESULTS", "Done populating video list")
    }

    // Get the `tokenId` and redirect URL from the server, and open the browser with the URL
    // When the user returns from the browser, the `onStart` method above is called and the auth continues from there
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

    // Display a list of channels the user has access to.
    // Usually only one is returned since the OAuth credentials relate to a single channel
    private fun showChannels() {
        Log.i("YOUTUBE_API", "Showing channels")
        setOutputText("")

        getChannelList { channels ->
            if (channels == null || channels.isEmpty()) {
                Log.i("YOUTUBE_API", "No results")
                Utility.showMessage(this, R.string.no_results)
            } else {
                Log.i("YOUTUBE_API", "Got data")
                val channelSummary = channels.map { channel ->
                    resources.getString(R.string.channel_description, channel.snippet.title, channel.statistics.viewCount)
                }


                setOutputText("Data retrieved using the YouTube Data API:\n\n" + TextUtils.join("\n\n", channelSummary))

                // A bit of a hack here so we don't need to fetch channel playlists and search for the Uploads playlist id.
                // Channel id seems to be the characters "UC" followed by an identifier.
                // The playlist id for Uploads seems to be "UU" followed by that same identifier
                // Therefore, to get the Uploads playlist id, strip the "UC" from the beginning of the channel id and add "UU" in its place
                val playlistId = "UU" + channels[0].id.substring(2)
                getPlaylistVideos(playlistId)
            }
        }
    }

    // Perform an API request to fetch channels from the API
    private fun getChannelList(onResult: (List<Channel>?) -> Unit) {
        Log.i("YOUTUBE_API", "Performing channel list action")
        MakeRequestTask<List<Channel>>({ apiClient ->
            Log.i("YOUTUBE_API", "data from api")
            val result = apiClient.channels().list("snippet,contentDetails,statistics")
                .setMine(true)
                .execute()

            Log.i("YOUTUBE_API", "Got channels ${result.items?.size ?: 0}")
            return@MakeRequestTask result.items?.toList() ?: emptyList()
        }, onResult).execute()
        Log.i("YOUTUBE_API", "Executed channel list item")
    }

    // Perform an API request to fetch and display the most recent videos in a playlist
    private fun getPlaylistVideos(playlistId: String) {
        Log.i("YOUTUBE_API", "Performing video list action")
        MakeRequestTask<List<PlaylistItem>>({ apiClient ->
            Log.i("YOUTUBE_API", "data from api")
            val result = apiClient.playlistItems().list("snippet,contentDetails,status")
                .setPlaylistId(playlistId)
                .setMaxResults(10)
                .execute()

            Log.i("YOUTUBE_API", "Got channels ${result.items?.size ?: 0}")
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
        Log.i("YOUTUBE_API", "Executed video list action")
    }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     *
     * The `HttpRequestInitializer` interface gives us access to the `initialize` method that the YouTube client calls before making a request
     * This allows us to set a request interceptor on the request object, which we use to set our own api token in the Bearer header
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
                Log.i("YOUTUBE_API", "Setting up the api client")

                // Stock objects required for the YouTube client
                val transport = AndroidHttp.newCompatibleTransport()
                val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()

                // The third parameter is important.
                // It tells the YouTube client to call the `initialize` method of this class before each request
                val apiClient = YouTube.Builder(transport, jsonFactory, this)
                    .setApplicationName("YouTube API Android Sample")
                    .build()

                // `fetchData` is a method passed in that uses the YouTube client to perform an API request
                Log.i("YOUTUBE_API", "Trying to get the data")
                fetchData(apiClient)
            } catch (e: Exception) {
                Log.e("YOUTUBE_API", "do background error", e)
                cancel(true, e)
                null
            }
        }

        // Before doInBackground is called, fetch the credentials
        override suspend fun onPreExecute(): Unit = suspendCoroutine { cont ->
            Log.i("YOUTUBE_API", "Pre execute")
            Utility.getAuthentication(this@MainActivity) { auth ->
                Log.i("YOUTUBE_API", "Fetched auth")
                this.auth = auth
                Log.i("YOUTUBE_API", "Resuming task flow")
                cont.resume(Unit)
            }
        }

        override fun onPostExecute(result: Result?) {
            Log.i("YOUTUBE_API", "Post execute")
            onResult(result)
        }

        override fun onCancelled(e: java.lang.Exception?) {
            Log.i("YOUTUBE_API", "Cancelled")
            if (e != null) {
                Log.e("YOUTUBE_API", "Error while making API request", e)
                Utility.showMessage(this@MainActivity, R.string.error_api_request)
            } else {
                Utility.showMessage(this@MainActivity, R.string.api_request_cancelled)
            }
        }

        override fun initialize(request: HttpRequest?) {
            Log.i("YOUTUBE_API", "Initializing the request")
            if (request == null) {
                Log.i("YOUTUBE_API", "No request, nothing to do")
                return
            }

            // By this point, we should have valid OAuth credentials. If not, something went wrong.
            Log.i("YOUTUBE_API", "Checking the auth")
            val auth = this.auth ?: throw Exception("Set authentication before running a request")

            // The RequestHandler class handles adding the credentials to the request
            Log.i("YOUTUBE_API", "Setting request handler")
            val handler = RequestHandler(auth)
            request.interceptor = handler
            request.unsuccessfulResponseHandler = handler
            Log.i("YOUTUBE_API", "Initialize complete")
        }
    }

    private inner class RequestHandler (val auth: AccessTokenResponse) : HttpExecuteInterceptor,
        HttpUnsuccessfulResponseHandler {

        // Set the authorization header using the OAuth credentials access token
        override fun intercept(request: HttpRequest) {
            Log.i("YOUTUBE_API", "Intercepted the request, adding an access token")
            request.headers.authorization = "Bearer ${ auth.access_token }"
        }

        override fun handleResponse(
            request: HttpRequest, response: HttpResponse, supportsRetry: Boolean
        ): Boolean {
            Log.i("YOUTUBE_API", "Request failed with the status" + response.statusCode.toString())
            // If the response was 401, mark the credentials as needing refreshing.
            // If the refresh token is no longer valid,
            // the API Server will not be able to return an access token and the user will be prompted to authenticate again
            if (response.statusCode == 401) {
                Utility.setRequiresRefresh(this@MainActivity)
            }
            return false
        }
    }
}
