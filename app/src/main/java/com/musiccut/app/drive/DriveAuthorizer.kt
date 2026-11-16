package com.musiccut.app.drive

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CancellationException

/**
 * Obtains an OAuth access token for the `drive.file` scope with the Google Identity
 * Authorization API. The user picks an account and grants access once; afterwards tokens are
 * returned silently.
 *
 * Setup: create an "Android" OAuth client in Google Cloud Console for package `com.musiccut.app`
 * with your signing certificate's SHA-1, and enable the Google Drive API for that project.
 */
class DriveAuthorizer(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<String>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val deferred = pending ?: return@registerForActivityResult
        pending = null
        if (result.resultCode != Activity.RESULT_OK) {
            deferred.completeExceptionally(CancellationException("Google 계정 권한 요청이 취소되었습니다"))
            return@registerForActivityResult
        }
        try {
            val auth = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data)
            val token = auth.accessToken
            if (token != null) deferred.complete(token) else deferred.completeExceptionally(IllegalStateException("No access token"))
        } catch (e: ApiException) {
            deferred.completeExceptionally(e)
        }
    }

    suspend fun accessToken(): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) {
            val intent = result.pendingIntent ?: error("Missing authorization intent")
            val deferred = CompletableDeferred<String>()
            pending = deferred
            launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
            return deferred.await()
        }
        return result.accessToken ?: error("No access token")
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
