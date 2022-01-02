## What is this?
This project came about due to a possibly niche use-case for accessing the YouTube API on an Android device.
By default, the API samples show how to authenticate using the device account manager.
Unfortunately, the Android account manager has not been updated to include a YouTube channel selector as the final step.
What this means is that anyone who wishes to use the API for any channel which is not the default is unable to use the Android account manager.
Full details are available in the [Bug Report][bug-report]

Two workarounds suggested have been:
- Go into the your YouTube settings and set the desired account to be default or
- Use a Content Manager Account and set `OnBehalfOfContentOwner` when making the API call

The first option ([anecdotally][default-channel-broken]) is no longer working, and the second option requires you to be a [YouTube partner][youtube-partner].
The only working solution seems to be to perform the authentication via the web OAuth flow and then capturing the credentials for use within the app. Using a WebView would have allowed posting a message containing the generated OAuth tokens back to the app to allow a seamless experience, but Google does not allow the OAuth flow to happen inside of a [WebView][no-oauth-webview] so we have to leave the app and use other means to get the tokens back into the app.

Once the tokens have been obtained, using them is fairly simple. Using a combination of the `HttpRequestInitializer` and `HttpExecuteInterceptor` interfaces, it's possible to provide the access token to the existing YouTube API class so the rest of the code in an application can remain unchanged.

There's a lot of moving parts and infrastructure detailed below. Depending on what infrastructure you have in place, you may be able to skip some or all of these steps. The aim is that you should be able to follow this document and have a working API integration at the end of it, however it is better used as a guide to show you what steps need to be taken so that you can come up with an implementation that better suits the tools and services that you are already using

## How does it work?
The user must be taken from the app, directed to a browser OAuth flow, and then returned to the app. The tokens generated from the OAuth flow should be brought back into the app with them. This is complicated by the fact that the OAuth flow requires a server endpoint in order to operate, so the process is as follows:

1. The app detects that it has no OAuth credentials for the user
1. The app communicates with your server to retrieve an identifier that will later be assigned to the OAuth credentials (`tokenId`) and an OAuth URL that includes the `tokenId` in the `state` parameter
1. The app opens a browser with the OAuth URL
1. The user completes the OAuth flow, selecting both the Google account and the YouTube channel that they want to use with the app
1. The OAuth server sends the user back to your server using the OAuth Redirect URL
1. Your server exchanges the OAuth authentication code for the OAuth Credential object, including access token, refresh token, and expiry date
1. Your server retrieves the `state` parameter from the response URL (aka the `tokenId`) and uses it to store these credentials temporarily
1. The user is instructed to return to the app
1. The app detects that the user has returned to it and uses the `tokenId` it received earlier to request the credentials from your server
1. Your server fetches the credentials assigned to the `tokenId` and returns them to the app
1. The app stores the credentials and uses them to perform an API requests
1. Later, when the credentials pass the expiry date, the app sends the refresh token to your server, which makes an OAuth request to generate a new access token to send back to the app

## Prerequisites
- You must have NodeJS (https://nodejs.org) and the yarn package manager (https://classic.yarnpkg.com) installed

## Implementation

### Glossary

#### __API Server__
A server you control which mediates between your app and the OAuth server

#### __OAuth Flow__
A series of steps the user takes in order to grant your app permission to access a service, in this case the YouTube Data API

#### __Secret Version__
When using the GCP Secret Manager to store secrets, each secret can have multiple versions. The version number starts at "1" and increments every time the value of the secret changes. In order to access a secret, you need to know what version the secret is at. This is listed when viewing the secret in the GCP Secret Manager.

#### __YouTube Data API__
A way to create, retrieve, update, and delete data about youtube channels, videos, playlists, etc. that the user has permission to manage

#### __YouTube OAuth Client__
This "client" is created by you and generates a client id and client secret that can be used to access the Youtube Data API. You also provide it with a list of "Authorised redirect URIs" that is used to return the user to the API Server once the OAuth Flow is complete

### YouTube OAuth Client Details
This is required to connect with the YouTube API.
If you are already using the YouTube API with a web app, you can skip this step.

1. Log in to the [GCP Console][console]
1. Go to the [APIs & Services][console-apis] screen 
1. If the Youtube API isn't listed in the table, find it in the [Library][console-apis-library] and enable it
1. Within the Youtube API dashboard, go to the [API credentials][console-apis-credentials] page 
1. Use the "Create Credentials" button at the top to create an OAuth Client and set the type to "Web Application"
1. Enter the required information and create the app, and then keep the tab open for later reference

### Backend setup
If you intend to use the API Server included in this sample project, the following setup must be completed:

1. Log in to the [GCP Console][gcp]
1. Go to the [Cloud Firestore][console-firestore] screen and ensure it is enabled
1. Go to the [welcome page][console-firestore-welcome] and enable a Native Mode firestore database
1. Go to the [Secret Manager][console-secret-manager] screen and ensure it is enabled
1. Add a secret using `youtube-rest-api-secret` as the secret name and the `Client Secret` from your `Youtube OAuth Client` as the secret value
1. Ensure the functions service account can access the secret by selecting the checkbox beside the secret you just created and then clicking `Add Principal`in the permissions box to the right so you can add the `Secret Manager Secret Accessor` role to the service account (if you don't know the service account, it is available in the function `Details` tab after you've deployed the function for the first time)

### Backend deployment
1. Copy the `gcp/functions/src/config.sample.json` file to `gcp/functions/src/config.json`, but make no other changes for now
1. Copy the `gcp/.firebaserc.sample` file to `gcp/.firebaserc` and add your GCP project id into it
1. In a command line, go to the `gcp/functions` directory
1. Run the `yarn` command to install all dependencies
1. Run `yarn firebase login` to log in to gcp
1. Run the `yarn deploy` command to deploy the rest api backend to your gcp project
1. Open up the [Function Details][console-function-details] screen
1. Go back to the `functions/src/config.sample.json` file
   - The value of the `clientId` field is the Client Id from the `Youtube OAuth Client`
   - The value of the `apiBaseUrl` field is the `Trigger URL` from the function details screen
   - The value of the `apiSecretVersion` field is Secret Version of the `youtube-rest-api-secret` secret created in the `Backend Setup` step. If you added the secret and never changed it then this version will be "1".
1. Run the `yarn deploy` command again to update the deployed function with these new values
1. In the `Youtube OAuth Client`, add an `authroized redirect uri` using the `Trigger URL` from the function details screen with the path `/tokenResponse` appended to the end

### Sample Android App
This repository contains a sample Android app used to demonstrate the OAuth flow.

To run it:
1. Open up this repo in Android Studio
1. Create a new `config.xml` xml file in the `res/values` directory and add a string resource with the name `auth_api_url` with the value being the `Trigger URL` from the [Function Details][console-function-details] screen.
   - If you are not using the code from this repository for your API Server, you will need to set this value to your API URL and change the API calls in the `MainActivity.kt` file to adhere to your API URL, request, and response format (these can be found by searching for `getString(R.string.auth_api_url)` in the file)
1. Connect your device or emulator
1. Run or debug the app

The app should automatically open a browser and invite you to pick the Google account and YouTube channel you want the app to authenticate as. When that's done you'll be asked to close the browser tab and return to the app, which should automatically display the last 10 videos you have uploaded.

### Next Steps
Once you have confirmed the sample app can successfully retrieve data from the YouTube Data API, you may use the code in your own app in accordance with the MIT license.

### Publishing the Youtube OAuth Client
While the `Youtube OAuth Client` is still in the "testing" state, any users will need to provide consent to the app every 7 days.
You can move your app into a different state by going to the [OAuth Consent][console-apis-consent] screen and clicking the `Publish` button.

[Anecdotal evidence][consent-evidence] suggests that even moving the app status to `Needs verification` will be enough to ensure the app does not need to re-acquire consent every 7 days, which will be fine if you only plan on using it for a personal project.
For public use, you will need to provide the verification requested before releasing your app.

[bug-report]: https://issuetracker.google.com/issues/35175143
[default-channel-broken]: https://issuetracker.google.com/issues/35175143#comment6
[youtube-partner]: https://issuetracker.google.com/issues/35175143#comment15
[no-oauth-webview]: https://www.google.com/url?q=https://developers.googleblog.com/2016/08/modernizing-oauth-interactions-in-native-apps.html&sa=D&usg=AOvVaw0cSF58W448s5zP1K8NotJw

[consent-evidence]: https://stackoverflow.com/a/65936387/989477
[console]: console.cloud.google.com
[console-apis]: https://console.cloud.google.com/apis/dashboard
[console-apis-consent]: https://console.cloud.google.com/apis/credentials/consent
[console-apis-credentials]: https://console.cloud.google.com/apis/api/youtube.googleapis.com/credentials
[console-apis-library]: https://console.cloud.google.com/apis/library
[console-firestore]: https://console.developers.google.com/apis/api/firestore.googleapis.com/overview
[console-firestore-welcome]: https://console.cloud.google.com/firestore/welcome
[console-function-details]: https://console.cloud.google.com/functions/details/us-central1/youtubeRestApi?tab=trigger
[console-secret-manager]: https://console.cloud.google.com/security/secret-manager
