package com.docloom_android.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.PrimaryButton

/** Screen frame shared by all auth pages, like the website's AuthShell/AuthCard. */
@Composable
private fun AuthShell(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DocLoom", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(32.dp))
        GlassCard(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    last: Boolean = false,
    onDone: () -> Unit = {},
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FooterLink(prompt: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun LoginScreen(vm: AuthViewModel, onSignup: () -> Unit, onForgot: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }

    AuthShell("Welcome back", "Sign in to your DocLoom workspace.") {
        state.error?.let { ErrorBanner(it) }
        Field("Work email", email, { email = it }, KeyboardType.Email, placeholder = "name@company.com")
        Field("Password", password, { password = it }, password = true, last = true, onDone = { vm.login(email, password) })
        TextButton(onClick = onForgot, modifier = Modifier.align(Alignment.End)) { Text("Forgot password?") }
        PrimaryButton("Continue", { vm.login(email, password) }, busy = state.busy, busyText = "Signing in…")
        FooterLink("New to DocLoom?", "Create an account", onSignup)
    }
}

@Composable
fun SignupScreen(vm: AuthViewModel, onLogin: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }

    AuthShell("Create your account", "Map, document and plan your project in one place.") {
        state.error?.let { ErrorBanner(it) }
        Field("Username", username, { username = it })
        Field("Work email", email, { email = it }, KeyboardType.Email, placeholder = "name@company.com")
        Field("Password", password, { password = it }, password = true, last = true, onDone = { vm.signup(username, email, password) })
        Text("At least 8 characters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton("Create account", { vm.signup(username, email, password) }, busy = state.busy, busyText = "Creating account…")
        FooterLink("Already have an account?", "Sign in", onLogin)
    }
}

@Composable
fun ForgotPasswordScreen(vm: AuthViewModel, onBack: () -> Unit, onHaveLink: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }

    AuthShell("Reset your password", "We'll email you a link to choose a new password.") {
        state.error?.let { ErrorBanner(it) }
        if (state.notice != null) {
            Text(state.notice!!, style = MaterialTheme.typography.bodyMedium)
            state.devResetUrl?.let { url ->
                Text("Development server: no email was sent. Use this link:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
            }
            PrimaryButton("I have the link", onHaveLink)
        } else {
            Field("Work email", email, { email = it }, KeyboardType.Email, last = true, onDone = { vm.forgotPassword(email) })
            PrimaryButton("Send reset link", { vm.forgotPassword(email) }, busy = state.busy, busyText = "Sending…")
        }
        FooterLink("Remembered it?", "Back to sign in", onBack)
    }
}

@Composable
fun ResetPasswordScreen(vm: AuthViewModel, initialToken: String?, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(initialToken ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }

    AuthShell("Choose a new password", "Paste the reset link from your email, then pick a new password.") {
        state.error?.let { ErrorBanner(it) }
        if (state.resetDone) {
            Text("Your password has been changed. Sign in with the new one.", style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("Back to sign in", onDone)
        } else {
            if (initialToken == null) Field("Reset link", link, { link = it }, KeyboardType.Uri)
            Field("New password", password, { password = it }, password = true)
            Field("Confirm password", confirm, { confirm = it }, password = true, last = true, onDone = { vm.resetPassword(link, password, confirm) })
            PrimaryButton("Change password", { vm.resetPassword(link, password, confirm) }, busy = state.busy, busyText = "Saving…")
            FooterLink("", "Back to sign in", onDone)
        }
    }
}
