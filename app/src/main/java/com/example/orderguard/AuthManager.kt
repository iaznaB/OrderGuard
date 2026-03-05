package com.example.orderguard

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthManager(private val context: Context) {

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            Scope(SheetsScopes.SPREADSHEETS),
            Scope(DriveScopes.DRIVE_FILE)
        )
        .build()

    val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    private val _userEmail = MutableStateFlow(
        GoogleSignIn.getLastSignedInAccount(context)?.email
    )
    val userEmail: StateFlow<String?> = _userEmail

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.result
            _userEmail.value = account?.email
        } catch (e: Exception) {
            _userEmail.value = null
        }
    }

    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _userEmail.value = null
        }
    }

    fun isSignedIn(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null
}