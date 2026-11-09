package com.docloom_android.core.auth

import com.docloom_android.core.model.AuthResponse
import com.docloom_android.core.model.ChangePasswordRequest
import com.docloom_android.core.model.DeleteAccountRequest
import com.docloom_android.core.model.ForgotPasswordRequest
import com.docloom_android.core.model.ForgotPasswordResponse
import com.docloom_android.core.model.LoginRequest
import com.docloom_android.core.model.MeResponse
import com.docloom_android.core.model.OkResponse
import com.docloom_android.core.model.ResetPasswordRequest
import com.docloom_android.core.model.SignupRequest
import com.docloom_android.core.model.ThemePreference
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST

/** `/api/auth/...`, implemented in docloom_website_be/src/auth/routes.rs. */
interface AuthApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/signup")
    suspend fun signup(@Body body: SignupRequest): AuthResponse

    @POST("api/auth/logout")
    suspend fun logout(): OkResponse

    @GET("api/auth/me")
    suspend fun me(): MeResponse

    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordResponse

    @POST("api/auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): OkResponse

    @GET("api/auth/preferences")
    suspend fun preferences(): ThemePreference

    @PATCH("api/auth/preferences")
    suspend fun updatePreferences(@Body body: ThemePreference): ThemePreference

    @POST("api/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): OkResponse

    // Retrofit's @DELETE can't carry a body; the backend wants the password in one.
    @HTTP(method = "DELETE", path = "api/auth/account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): OkResponse
}
