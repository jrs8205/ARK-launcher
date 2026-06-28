package org.arkikeskus.launcher.feature.backup.drive

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Scope for the hidden per-app Drive folder (non-sensitive — no Google verification needed). */
private const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

class DriveAuth(private val activity: Activity) {

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    /** Returns a usable access token, launching the consent UI if authorization is pending. */
    suspend fun authorizeOrNull(): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}

/**
 * Non-interactive Drive authorization for use in background workers.
 * Never launches consent UI — safe to call from [androidx.work.CoroutineWorker].
 */
object TokenProvider {

    private fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    /**
     * Returns a valid Drive access token if authorization was already granted, or `null` if
     * the authorization requires user interaction ([AuthorizationResult.hasResolution] is true)
     * or if the authorization call fails.
     *
     * Uses application [context] — does not require an Activity.
     */
    suspend fun silentToken(context: Context): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(authRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) cont.resume(null)
                else cont.resume(result.accessToken)
            }
            .addOnFailureListener { cont.resume(null) }
    }
}
