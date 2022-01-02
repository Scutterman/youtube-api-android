import { auth } from '@googleapis/oauth2'
import { SecretManagerServiceClient } from '@google-cloud/secret-manager'
import * as functions from 'firebase-functions'
import * as config from './config.json'
import * as admin from 'firebase-admin'
import { Credentials } from 'googleapis-common/node_modules/google-auth-library'

admin.initializeApp()

interface GenerateAuthUrlResponse {
  url: string
  tokenId: string
}

interface RefreshTokenResponse {
  token: string
}

enum StoredTokenStatus {
  PENDING_AUTH = 'PENDING_AUTH',
  HAS_AUTH = 'HAS_AUTH'
}

interface StoredToken {
  status: StoredTokenStatus
  tokens: Credentials | null
}

type StoredTokenDocument = FirebaseFirestore.CollectionReference<StoredToken>

let _tokenTemporaryStorage: StoredTokenDocument | undefined

function tokenTemporaryStorage(): StoredTokenDocument {
  if (_tokenTemporaryStorage == null) {
    _tokenTemporaryStorage = admin
      .firestore()
      .collection('tokenTemporaryStorage') as StoredTokenDocument
  }

  return _tokenTemporaryStorage
}

let _secretManager: SecretManagerServiceClient | undefined

function secretManager(): SecretManagerServiceClient {
  if (_secretManager == null) {
    _secretManager = new SecretManagerServiceClient()
  }

  return _secretManager
}

async function getSecret(secretName: string, version: string): Promise<string | null> {
  const name = `projects/${ process.env.GCLOUD_PROJECT }/secrets/${ secretName }/versions/${ version }`
  const [secretVersion] = await secretManager().accessSecretVersion({ name })
  return secretVersion.payload?.data?.toString() ?? null
}

async function getApiKey(): Promise<string> {
  const secret = await getSecret('youtube-rest-api-secret', config.apiSecretVersion)
  if (!secret) {
    throw new Error('Youtube API key not available, add it to secret manager.')
  }

  return secret
}

export const youtubeRestApi = functions.https.onRequest(async (request, response) => {
  try {
    if (request.path === '/generateAuthUrl') {
      await generateAuthUrl(response)
    } else if (request.path === '/tokenResponse') {
      await tokenResponse(request, response)
    } else if (request.path === '/getStoredToken') {
      await getStoredToken(request, response)
    } else if (request.path === '/refreshToken') {
      await refreshToken(request, response)
    } else {
      response.sendStatus(404)
    }
  } catch (e) {
    functions.logger.error('Something went wrong with the rest api', e)
    response.sendStatus(500)
  }
})

async function generateAuthUrl(response: functions.Response<GenerateAuthUrlResponse>): Promise<void> {
  const oauth2Client = new auth.OAuth2(
    config.clientId,
    await getApiKey(),
    config.apiBaseUrl + '/tokenResponse'
  )

  const scopes = [
    'https://www.googleapis.com/auth/youtube.readonly',
    'https://www.googleapis.com/auth/youtube.upload'
  ]

  const doc = await tokenTemporaryStorage().add({
    status: StoredTokenStatus.PENDING_AUTH,
    tokens: null
  })

  const url = oauth2Client.generateAuthUrl({
    // offline means include the refresh_token
    access_type: 'offline',
    prompt: 'consent',
    scope: scopes,
    state: doc.id
  })

  response.send({
    url,
    tokenId: doc.id
  })
}

async function tokenResponse(request: functions.https.Request, response: functions.Response<string>): Promise<void> {
  const error = request.query['error'] as string | undefined
  const code = request.query['code'] as string | undefined
  const state = request.query['state'] as string | undefined

  if (error != null) {
    functions.logger.error('Error in authentication', error, request.query)
    response.status(401).send('401: ' + error)
    return
  } else if (code == null) {
    functions.logger.error('No code returned from api', request.query)
    response.sendStatus(401)
    return
  } else if (state == null) {
    functions.logger.error('No state returned from api', request.query)
    response.sendStatus(401)
    return
  }

  const doc = await tokenTemporaryStorage().doc(state).get()
  const data = doc.data()
  if (!doc.exists || data == null) {
    functions.logger.error('Invalid state', state)
    response.sendStatus(401)
    return
  }

  const oauth2Client = new auth.OAuth2(config.clientId, await getApiKey(), config.apiBaseUrl + '/tokenResponse')
  const { tokens } = await oauth2Client.getToken(code)

  try {
    await doc.ref.update({ status: StoredTokenStatus.HAS_AUTH, tokens } as Partial<StoredToken>)
  } catch (e) {
    functions.logger.error('Could not update the doc', e)
    response.sendStatus(401)
    return
  }

  response.send('<html><body>Authentication successful. Close this window and return to the application</body></html>')
}

async function getStoredToken(request: functions.https.Request, response: functions.Response<Credentials>): Promise<void> {
  const tokenId = request.query['tokenId'] as string | undefined
  if (tokenId == null) {
    functions.logger.error('Token id required in order to fetch stored token', request.query)
    response.sendStatus(401)
    return
  }

  const doc = await tokenTemporaryStorage().doc(tokenId).get()
  const data = doc.data()
  if (!doc.exists || data == null) {
    functions.logger.error('No token data available', tokenId)
    response.sendStatus(401)
    return
  }

  if (data.status !== StoredTokenStatus.HAS_AUTH || data.tokens == null) {
    functions.logger.error('No token available', data.status)
    response.sendStatus(401)
    return
  }

  // We don't want to keep tokens once they've been requested once.
  await doc.ref.delete()

  response.send(data.tokens)
}

async function refreshToken(request: functions.https.Request, response: functions.Response<RefreshTokenResponse>): Promise<void> {
  const refreshToken = request.body.refreshToken as string | undefined
  if (refreshToken == null) {
    functions.logger.error('Refresh token is missing', request.body)
    response.sendStatus(401)
    return
  }

  const oauth2Client = new auth.OAuth2(config.clientId, await getApiKey(), config.apiBaseUrl + '/tokenResponse')
  oauth2Client.setCredentials({
    refresh_token: refreshToken
  })

  const { token } = await oauth2Client.getAccessToken()
  if (token == null) {
    functions.logger.error('No token returned from api')
    response.sendStatus(401)
    return
  }

  response.send({ token })
}
