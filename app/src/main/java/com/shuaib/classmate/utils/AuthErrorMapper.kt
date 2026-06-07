package com.shuaib.classmate.utils

import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

object AuthErrorMapper {
    fun signupMessage(error: Exception): String {
        firebaseCodeMessage(error)?.let { return it }
        return when (error) {
            is FirebaseAuthUserCollisionException -> "This email is already registered."
            is FirebaseAuthInvalidCredentialsException -> "Enter a valid email address."
            is FirebaseAuthWeakPasswordException -> "Password is too weak."
            is FirebaseNetworkException -> "Network error. Try again."
            else -> "Could not create account."
        }
    }

    fun loginMessage(error: Exception): String {
        firebaseCodeMessage(error)?.let { return it }
        return when (error) {
            is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
            is FirebaseAuthInvalidUserException -> "No account found with these credentials."
            is FirebaseNetworkException -> "Network error. Try again."
            else -> "Authentication failed."
        }
    }

    fun googleMessage(error: Exception): String {
        firebaseCodeMessage(error)?.let { return it }
        return when (error) {
            is FirebaseNetworkException -> "Network error. Try again."
            is FirebaseAuthUserCollisionException -> "This email is already registered."
            is FirebaseAuthInvalidCredentialsException -> "Authentication credential is invalid."
            else -> "Authentication failed."
        }
    }

    /**
     * Human-readable cause for [com.google.android.gms.common.api.ApiException] from
     * [com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent].
     */
    fun googleSignInPickerMessage(statusCode: Int): String {
        return when (statusCode) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> ""
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Sign-in is already in progress. Wait a moment and try again."
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Sign-in failed. Try again later."
            CommonStatusCodes.DEVELOPER_ERROR ->
                "This sign-in option is temporarily unavailable."
            CommonStatusCodes.NETWORK_ERROR -> "Network error. Check your connection and try again."
            CommonStatusCodes.INTERNAL_ERROR -> "Sign-in service error. Try again in a moment."
            CommonStatusCodes.TIMEOUT -> "Sign-in timed out. Try again."
            CommonStatusCodes.API_NOT_CONNECTED -> "This sign-in option is temporarily unavailable."
            else ->
                "Sign-in failed (code $statusCode)."
        }
    }

    fun isGoogleSignInCancelled(statusCode: Int): Boolean =
        statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED

    fun forgotPasswordMessage(error: Exception): String {
        firebaseCodeMessage(error)?.let { return it }
        return when (error) {
            is FirebaseNetworkException -> "Network error. Try again."
            is FirebaseAuthInvalidUserException -> "No account found with this email."
            else -> "Failed to send reset email."
        }
    }

    private fun firebaseCodeMessage(error: Exception): String? {
        return when ((error as? FirebaseAuthException)?.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method is not enabled in Firebase Console."
            "ERROR_INVALID_CREDENTIAL" -> "Email or password is incorrect."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "This email is already registered."
            "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
            "ERROR_WEAK_PASSWORD" -> "Password is too weak."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Try again."
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                "This email is already used with email/password or another provider. Sign in with that method, or link accounts in Firebase."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Try again later."
            else -> null
        }
    }
}
