package uk.co.cgfindies.youtubeAPIAndroidSample

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class AccessTokenResponse(
    val access_token: String,
    val expiry_date: Long,
    val token_type: String,
    val scope: String,
    val refresh_token: String
)

class Utility {
    companion object {
        fun showMessage(activity: Activity, @StringRes messageId: Int) {
            val view = activity.findViewById<View>(android.R.id.content)
            Snackbar.make(view, messageId, Snackbar.LENGTH_LONG).show()
        }

        // fetch the authentication from the preferences database.
        // If the access token has expired, we ask the API server to give us a new access token using our refresh token
        // If the API server is unable to provide a new access token,
        // we assume the credentials are no longer valid and send the user back through the OAuth Flow
        fun getAuthentication(context: Context, onComplete: (accessTokenResponse: AccessTokenResponse?) -> Unit) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            val accessToken = youtubePrefs.getString("accessToken", null)
            val expiryDate = youtubePrefs.getLong("expiryDate", -1)
            val tokenType = youtubePrefs.getString("tokenType", null)
            val scope = youtubePrefs.getString("scope", null)
            val refreshToken = youtubePrefs.getString("refreshToken", null)
            val requiresRefresh = youtubePrefs.getBoolean("requiresRefresh", false)

            if (accessToken == null || expiryDate < 0 || tokenType == null || scope == null || refreshToken == null) {
                Log.i("GET_AUTHENTICATION", "One or more expected parameters is null, cannot construct AccessTokenResponse")
                onComplete(null)
                return
            }

            val time = System.currentTimeMillis()
            Log.i("GET_AUTHENTICATION", "Access token expires at $expiryDate, current time is $time")
            if (expiryDate >= time && !requiresRefresh) {
                Log.i("GET_AUTHENTICATION", "Token still valid, returning AccessTokenResponse")
                onComplete(
                    AccessTokenResponse(
                        accessToken,
                        expiryDate,
                        tokenType,
                        scope,
                        refreshToken
                    )
                )
                return
            }

            // The access token needs refreshing, so ask the API Server to do that
            val url = context.getString(R.string.auth_api_url)
            val body =  JSONObject(mapOf((Pair("refreshToken", refreshToken))))

            Log.i("GET_AUTHENTICATION", "Making request to refresh the access token")
            val authRequest = JsonObjectRequest(
                Request.Method.POST, "$url/refreshToken", body,
                { response ->
                    Log.i("GET_AUTHENTICATION", "Got response")
                    val refreshedAccessToken = response?.getString("token")
                    if (refreshedAccessToken == null) {
                        Log.i("GET_AUTHENTICATION", "New access token is null")

                        // Nuclear approach.
                        // If the access token refresh failed, it's probably the refresh token is no longer valid.
                        // This can happen if the user revokes access to the app.
                        // Assume this is the case, and remove stored credentials
                        resetCredentials(context)

                        onComplete(null)
                    } else {
                        youtubePrefs.edit()
                            .putString("accessToken", refreshedAccessToken)
                            .putBoolean("requiresRefresh", false)
                            .apply()

                        Log.i("GET_AUTHENTICATION", "Stored new access token and returning AccessTokenResponse object")
                        onComplete(AccessTokenResponse(refreshedAccessToken, expiryDate, tokenType, scope, refreshToken))
                    }
                },
                { error ->
                    Log.e("REFRESH_ACCESS_TOKEN", "Could not refresh the access token", error)
                    onComplete(null)
                }
            )

            val queue = Volley.newRequestQueue(context)
            queue.add(authRequest)
        }

        // Store OAuth credentials in a preferences database for later use
        fun setAuthentication(auth: AccessTokenResponse, context: Context) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            youtubePrefs.edit()
                .putString("accessToken", auth.access_token)
                .putLong("expiryDate", auth.expiry_date)
                .putString("tokenType", auth.token_type)
                .putString("scope", auth.scope)
                .putString("refreshToken", auth.refresh_token)
                .putBoolean("requiresRefresh", false)
                .apply()
        }

        // Signal that the access token has expired and requires a refresh
        fun setRequiresRefresh(context: Context) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            youtubePrefs.edit()
                .putBoolean("requiresRefresh", true)
                .apply()
        }

        // Revoke the credentials.
        // Useful if the user wishes to log out
        // or if the token has been refreshed and is still returning 401 responses
        fun resetCredentials(context: Context) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            youtubePrefs.edit()
                .remove("accessToken")
                .remove("expiryDate")
                .remove("tokenType")
                .remove("scope")
                .remove("refreshToken")
                .remove("requiresRefresh")
                .apply()
        }
    }
}
