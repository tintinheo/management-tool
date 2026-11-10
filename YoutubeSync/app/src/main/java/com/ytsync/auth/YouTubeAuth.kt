package com.ytsync.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google Identity Services authorization. No client id or secret lives in the app: Google matches the
 * Android OAuth client by package name + signing SHA-1 registered in Cloud Console. Once the user has
 * consented, [token] returns a fresh 1-hour access token silently — also from a background worker.
 */
object YouTubeAuth {
    val scopes = listOf(
        Scope("https://www.googleapis.com/auth/youtube.upload"),
        // Read back privacy / 18+ status and the channel name.
        Scope("https://www.googleapis.com/auth/youtube.readonly"),
    )

    /**
     * Asked for only when the user renames videos that are already on YouTube (videos.update rejects
     * youtube.upload). Background sync keeps using [scopes], so it never needs this extra consent.
     */
    val manageScopes = scopes + Scope("https://www.googleapis.com/auth/youtube.force-ssl")

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** User must (re)consent in the UI — launch [intent]. */
        data class NeedsUser(val intent: PendingIntent) : Result
        data class Error(val message: String) : Result
    }

    private fun request(s: List<Scope>) = AuthorizationRequest.builder().setRequestedScopes(s).build()

    suspend fun token(context: Context, requested: List<Scope> = scopes): Result = try {
        Identity.getAuthorizationClient(context).authorize(request(requested)).await().toResult()
    } catch (e: Exception) {
        Result.Error(e.message ?: e.javaClass.simpleName)
    }

    /** After the consent screen returns. */
    fun fromIntent(context: Context, data: Intent?): Result = try {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).toResult()
    } catch (e: Exception) {
        Result.Error(e.message ?: e.javaClass.simpleName)
    }

    /** Drop a token YouTube rejected (401) so the next [token] call fetches a new one. */
    suspend fun invalidate(context: Context, accessToken: String) {
        runCatching {
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
        }
    }

    private fun AuthorizationResult.toResult(): Result {
        val pi = pendingIntent
        return when {
            hasResolution() && pi != null -> Result.NeedsUser(pi)
            accessToken != null -> Result.Token(accessToken!!)
            else -> Result.Error("Không nhận được access token")
        }
    }
}
