@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.vktokenchecker

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.POST
import retrofit2.http.Query

private const val API_VERSION = "5.199"

data class VkResponse<T>(
    val response: T? = null,
    val error: VkError? = null
)

data class VkError(
    val error_code: Int,
    val error_msg: String
)

data class User(
    val id: Int,
    val first_name: String,
    val last_name: String
)

data class CallResponse(
    val join_link: String? = null,
    val url: String? = null
)

interface VkApiService {
    @POST("users.get")
    suspend fun getUsers(
        @Query("access_token") token: String,
        @Query("v") version: String = API_VERSION
    ): VkResponse<List<User>>

    @POST("calls.start")
    suspend fun startCall(
        @Query("access_token") token: String,
        @Query("v") version: String = API_VERSION
    ): VkResponse<CallResponse>
}

object VkApiClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.vk.com/method/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(VkApiService::class.java)

    suspend fun checkToken(token: String): TokenCheckResult {
        return try {
            val response = api.getUsers(token)
            if (response.error != null) {
                when (response.error.error_code) {
                    5, 1117 -> TokenCheckResult.Invalid(token, "Невалидный токен")
                    6 -> TokenCheckResult.Error(token, "Rate limit")
                    else -> TokenCheckResult.Error(token, "Ошибка ${response.error.error_code}: ${response.error.error_msg}")
                }
            } else {
                val user = response.response?.firstOrNull()
                if (user != null) {
                    TokenCheckResult.Valid(
                        token,
                        user.id,
                        "${user.first_name} ${user.last_name}".trim()
                    )
                } else {
                    TokenCheckResult.Error(token, "Пустой ответ")
                }
            }
        } catch (e: Exception) {
            TokenCheckResult.Error(token, e.message ?: "Неизвестная ошибка")
        }
    }

    suspend fun createCall(token: String): CreateCallResult {
        return try {
            val response = api.startCall(token)
            if (response.error != null) {
                CreateCallResult.Error(response.error.error_msg)
            } else {
                val link = response.response?.join_link ?: response.response?.url
                if (link != null) {
                    CreateCallResult.Success(link)
                } else {
                    CreateCallResult.Error("Не удалось получить ссылку")
                }
            }
        } catch (e: Exception) {
            CreateCallResult.Error(e.message ?: "Неизвестная ошибка")
        }
    }
}

sealed class TokenCheckResult {
    data class Valid(val token: String, val id: Int, val name: String) : TokenCheckResult()
    data class Invalid(val token: String, val reason: String) : TokenCheckResult()
    data class Error(val token: String, val reason: String) : TokenCheckResult()
}

sealed class CreateCallResult {
    data class Success(val link: String) : CreateCallResult()
    data class Error(val message: String) : CreateCallResult()
}

data class TokenStatus(
    val token: String,
    val status: TokenCheckResult,
    val expanded: Boolean = false
)

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

enum class ColorTheme {
    DYNAMIC, DARK, LIGHT
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = application.getSharedPreferences("vk_token_checker", Context.MODE_PRIVATE)
    
    private val _tokens = MutableStateFlow<List<String>>(emptyList())
    val tokens: StateFlow<List<String>> = _tokens.asStateFlow()

    private val _results = MutableStateFlow<List<TokenStatus>>(emptyList())
    val results: StateFlow<List<TokenStatus>> = _results.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _callLink = MutableStateFlow<String?>(null)
    val callLink: StateFlow<String?> = _callLink.asStateFlow()

    private val _callMessage = MutableStateFlow<String?>(null)
    val callMessage: StateFlow<String?> = _callMessage.asStateFlow()

    private val _isCreatingCall = MutableStateFlow(false)
    val isCreatingCall: StateFlow<Boolean> = _isCreatingCall.asStateFlow()

    private val _selectedTokenForCall = MutableStateFlow<String?>(null)
    val selectedTokenForCall: StateFlow<String?> = _selectedTokenForCall.asStateFlow()

    private fun updateSelectedTokenForCall() {
        val firstValidToken = _results.value.firstOrNull { it.status is TokenCheckResult.Valid }?.token
        if (firstValidToken != null && _selectedTokenForCall.value == null) {
            _selectedTokenForCall.value = firstValidToken
        }
    }

    private val _webDavConfig = MutableStateFlow(WebDavConfig(
        url = prefs.getString("webdav_url", "") ?: "",
        username = prefs.getString("webdav_username", "") ?: "",
        password = prefs.getString("webdav_password", "") ?: ""
    ))
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    private val _autoSyncWebDav = MutableStateFlow(prefs.getBoolean("webdav_auto_sync", false))
    val autoSyncWebDav: StateFlow<Boolean> = _autoSyncWebDav.asStateFlow()

    private val _colorTheme = MutableStateFlow(ColorTheme.valueOf(prefs.getString("color_theme", ColorTheme.DYNAMIC.name) ?: ColorTheme.DYNAMIC.name))
    val colorTheme: StateFlow<ColorTheme> = _colorTheme.asStateFlow()

    fun setColorTheme(theme: ColorTheme) {
        _colorTheme.value = theme
        prefs.edit().putString("color_theme", theme.name).apply()
    }

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private var checkJob: Job? = null

    init {
        // Загружаем сохранённые токены
        loadSavedTokens()
    }

    private fun loadSavedTokens() {
        val savedTokens = prefs.getString("saved_tokens", "") ?: ""
        if (savedTokens.isNotEmpty()) {
            _tokens.value = savedTokens.split("\n").filter { it.isNotEmpty() }
            // После загрузки токенов загружаем результаты
            loadSavedResults()
        }
    }

    private fun saveTokens() {
        val tokensText = _tokens.value.joinToString("\n")
        prefs.edit().putString("saved_tokens", tokensText).apply()
    }

    private fun saveResults() {
        val resultsData = _results.value.map { result ->
            "${result.token}::${result.status::class.simpleName}::${
                when (result.status) {
                    is TokenCheckResult.Valid -> "${result.status.id}::${result.status.name}"
                    is TokenCheckResult.Invalid -> result.status.reason
                    is TokenCheckResult.Error -> result.status.reason
                }
            }"
        }.joinToString("||")
        prefs.edit().putString("saved_results", resultsData).apply()
    }

    private fun loadSavedResults() {
        val savedResults = prefs.getString("saved_results", "") ?: ""
        if (savedResults.isNotEmpty()) {
            _results.value = savedResults.split("||").mapNotNull { line ->
                val parts = line.split("::")
                if (parts.size >= 2) {
                    val token = parts[0]
                    val statusType = parts[1]
                    when (statusType) {
                        "Valid" -> {
                            if (parts.size >= 4) {
                                val id = parts[2].toIntOrNull() ?: 0
                                val name = parts[3]
                                TokenStatus(token, TokenCheckResult.Valid(token, id, name))
                            } else null
                        }
                        "Invalid" -> {
                            val reason = parts.getOrNull(2) ?: ""
                            TokenStatus(token, TokenCheckResult.Invalid(token, reason))
                        }
                        "Error" -> {
                            val reason = parts.getOrNull(2) ?: ""
                            TokenStatus(token, TokenCheckResult.Error(token, reason))
                        }
                        else -> null
                    }
                } else null
            }
            updateSelectedTokenForCall()
        }
    }

    private fun saveWebDavConfig() {
        prefs.edit()
            .putString("webdav_url", _webDavConfig.value.url)
            .putString("webdav_username", _webDavConfig.value.username)
            .putString("webdav_password", _webDavConfig.value.password)
            .apply()
    }

    fun toggleAutoSyncWebDav(enabled: Boolean) {
        _autoSyncWebDav.value = enabled
        prefs.edit().putBoolean("webdav_auto_sync", enabled).apply()
    }

    fun syncWebDavOnStartup() {
        if (_autoSyncWebDav.value && _webDavConfig.value.url.isNotBlank()) {
            downloadFromWebDav()
        }
    }

    fun loadTokens(text: String) {
        _tokens.value = text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        _results.value = emptyList()
        _callLink.value = null
        _callMessage.value = null
        _selectedTokenForCall.value = null
        saveTokens()
        saveResults()
    }

    fun checkAllTokens() {
        if (_tokens.value.isEmpty()) return
        
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _isChecking.value = true
            _progress.value = 0
            val allResults = mutableListOf<TokenStatus>()
            val total = _tokens.value.size

            try {
                for ((index, token) in _tokens.value.withIndex()) {
                    val result = withContext(Dispatchers.IO) {
                        VkApiClient.checkToken(token)
                    }
                    allResults.add(TokenStatus(token, result))
                    _results.value = allResults.toList()
                    _progress.value = ((index + 1) * 100) / total
                    delay(340)
                }
                saveResults()
                updateSelectedTokenForCall()
            } catch (e: Exception) {
                // Обработка ошибок
            }

            _isChecking.value = false
        }
    }

    fun setSelectedTokenForCall(token: String) {
        _selectedTokenForCall.value = token
    }

    fun createCall() {
        viewModelScope.launch {
            val tokenToUse = _selectedTokenForCall.value
                ?: _results.value.firstOrNull { it.status is TokenCheckResult.Valid }?.token

            if (tokenToUse != null) {
                _isCreatingCall.value = true
                _callMessage.value = null
                val result = withContext(Dispatchers.IO) {
                    VkApiClient.createCall(tokenToUse)
                }
                when (result) {
                    is CreateCallResult.Success -> {
                        _callLink.value = result.link
                        _callMessage.value = null
                    }
                    is CreateCallResult.Error -> {
                        _callLink.value = null
                        _callMessage.value = "❌ Ошибка: ${result.message}"
                    }
                }
                _isCreatingCall.value = false
            }
        }
    }

    fun updateWebDavConfig(url: String, username: String, password: String) {
        _webDavConfig.value = WebDavConfig(url, username, password)
        saveWebDavConfig()
    }

    fun downloadFromWebDav() {
        viewModelScope.launch {
            _isDownloading.value = true
            val config = _webDavConfig.value
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL(config.url)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 30000
                    connection.readTimeout = 30000
                    connection.requestMethod = "GET"
                    
                    val auth = "${config.username}:${config.password}"
                    val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
                    connection.setRequestProperty("Authorization", "Basic $encodedAuth")
                    
                    val responseCode = connection.responseCode
                    
                    if (responseCode != 200) {
                        return@withContext Result.failure<String>(Exception("HTTP $responseCode: ${connection.responseMessage}"))
                    }
                    
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(content)
                } catch (e: Exception) {
                    Result.failure<String>(e)
                }
            }

            result.onSuccess { content ->
                loadTokens(content)
            }.onFailure { error ->
                val context = getApplication<Application>().applicationContext
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка WebDAV: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }

            _isDownloading.value = false
        }
    }

    fun toggleTokenExpand(token: String) {
        _results.value = _results.value.map {
            if (it.token == token) it.copy(expanded = !it.expanded) else it
        }
    }

    fun clearAll() {
        _tokens.value = emptyList()
        _results.value = emptyList()
        _callLink.value = null
        _callMessage.value = null
        _selectedTokenForCall.value = null
        _progress.value = 0
        checkJob?.cancel()
        _isChecking.value = false
        saveTokens()
        saveResults()
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VK Call Link", text))
        Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TokenCheckerApp(viewModel: MainViewModel = viewModel()) {
    val tokens by viewModel.tokens.collectAsState()
    val results by viewModel.results.collectAsState()
    val isChecking by viewModel.isChecking.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val callLink by viewModel.callLink.collectAsState()
    val callMessage by viewModel.callMessage.collectAsState()
    val isCreatingCall by viewModel.isCreatingCall.collectAsState()
    val selectedTokenForCall by viewModel.selectedTokenForCall.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val autoSyncWebDav by viewModel.autoSyncWebDav.collectAsState()
    val colorTheme by viewModel.colorTheme.collectAsState()
    val context = LocalContext.current

    var tokenInput by remember { mutableStateOf("") }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var webDavUrl by remember { mutableStateOf(webDavConfig.url) }
    var webDavUsername by remember { mutableStateOf(webDavConfig.username) }
    var webDavPassword by remember { mutableStateOf(webDavConfig.password) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("VK Token Checker")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = {
                                    if (webDavConfig.url.isNotBlank()) {
                                        viewModel.downloadFromWebDav()
                                    } else {
                                        webDavUrl = webDavConfig.url
                                        webDavUsername = webDavConfig.username
                                        webDavPassword = webDavConfig.password
                                        showWebDavDialog = true
                                    }
                                },
                                onLongClick = {
                                    webDavUrl = webDavConfig.url
                                    webDavUsername = webDavConfig.username
                                    webDavPassword = webDavConfig.password
                                    showWebDavDialog = true
                                },
                                enabled = !isDownloading
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Загрузить из WebDAV",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Поле ввода токенов
            item {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text("Токены") },
                    placeholder = { 
                        Text(
                            "Вставьте токены здесь...\nКаждый токен с новой строки",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.extraLarge
                )
            }

            // Кнопки действий
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.loadTokens(tokenInput) },
                        modifier = Modifier.weight(1f),
                        enabled = tokenInput.isNotBlank() && !isChecking,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Загрузить")
                    }

                    Button(
                        onClick = { viewModel.checkAllTokens() },
                        modifier = Modifier.weight(1f),
                        enabled = tokens.isNotEmpty() && !isChecking,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Проверить")
                    }

                    Button(
                        onClick = { viewModel.clearAll() },
                        modifier = Modifier.weight(1f),
                        enabled = !isChecking && (tokens.isNotEmpty() || results.isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Очистить")
                    }
                }
            }

            // Прогресс бар
            if (isChecking) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Проверка токенов...",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$progress%",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }

            // Выбор токена для звонка
            val validTokens = results.filter { it.status is TokenCheckResult.Valid }
            if (validTokens.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Выберите токен для звонка",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Icon(
                                    imageVector = Icons.Default.People,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                validTokens.forEach { item ->
                                    val status = item.status as TokenCheckResult.Valid
                                    val isSelected = selectedTokenForCall == item.token
                                    
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.setSelectedTokenForCall(item.token) },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (isSelected) 
                                            MaterialTheme.colorScheme.primaryContainer 
                                        else 
                                            Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = status.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "id${status.id}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = item.token.take(20) + "...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { viewModel.setSelectedTokenForCall(item.token) },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Button(
                                onClick = { viewModel.createCall() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isCreatingCall,
                                shape = MaterialTheme.shapes.large,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            ) {
                                if (isCreatingCall) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Call,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isCreatingCall) "Создание звонка..." else "Создать групповой звонок",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }

            // Ссылка на звонок
            callLink?.let { link ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Звонок создан!",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.copyToClipboard(context, link) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = link,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f),
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }

            // Ошибка создания звонка
            callMessage?.let { message ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Статистика
            if (results.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val validCount = results.count { it.status is TokenCheckResult.Valid }
                        val invalidCount = results.count { it.status is TokenCheckResult.Invalid }
                        val errorCount = results.count { it.status is TokenCheckResult.Error }

                        StatChip(Icons.Default.TaskAlt, "Валидные", validCount.toString(), MaterialTheme.colorScheme.primary)
                        StatChip(Icons.Default.Block, "Невалидные", invalidCount.toString(), MaterialTheme.colorScheme.error)
                        StatChip(Icons.Default.Report, "Ошибки", errorCount.toString(), MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            // Результаты
            if (results.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Результаты",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "${results.size} токенов",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(results.size, key = { index -> "result_${index}_${results[index].token.hashCode()}" }) { index ->
                    TokenResultCard(results[index], { viewModel.toggleTokenExpand(results[index].token) })
                }
            }
        }

        // Диалог WebDAV
        if (showWebDavDialog) {
            AlertDialog(
                onDismissRequest = { showWebDavDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { 
                    Text(
                        "Загрузка из WebDAV",
                        style = MaterialTheme.typography.headlineSmall
                    ) 
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = webDavUrl,
                            onValueChange = { webDavUrl = it },
                            label = { Text("URL файла") },
                            placeholder = { Text("https://example.com/tokens.txt") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null)
                            }
                        )
                        OutlinedTextField(
                            value = webDavUsername,
                            onValueChange = { webDavUsername = it },
                            label = { Text("Имя пользователя") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            }
                        )
                        OutlinedTextField(
                            value = webDavPassword,
                            onValueChange = { webDavPassword = it },
                            label = { Text("Пароль") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.updateWebDavConfig(webDavUrl, webDavUsername, webDavPassword)
                            viewModel.downloadFromWebDav()
                            showWebDavDialog = false
                        },
                        enabled = webDavUrl.isNotBlank() && !isDownloading,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Загрузить")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showWebDavDialog = false },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Отмена")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraLarge
            )
        }

        // Диалог настроек
        if (showSettings) {
            SettingsDialog(
                onDismiss = { showSettings = false },
                autoSyncWebDav = autoSyncWebDav,
                onToggleAutoSync = { viewModel.toggleAutoSyncWebDav(it) },
                colorTheme = colorTheme,
                onSetColorTheme = { viewModel.setColorTheme(it) }
            )
        }
    }
}

@Composable
fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: String, color: Color) {
    Surface(
        modifier = Modifier.background(color.copy(alpha = 0.2f), MaterialTheme.shapes.small),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = count,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    color = color.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    autoSyncWebDav: Boolean,
    onToggleAutoSync: (Boolean) -> Unit,
    colorTheme: ColorTheme,
    onSetColorTheme: (ColorTheme) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                "Настройки",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Material Design 3",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "by ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "martyr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = " & ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "maffin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Divider()

                Text(
                    text = "Цветовая тема",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeOption(
                        title = "Dynamic Color",
                        subtitle = "Адаптация под обои",
                        selected = colorTheme == ColorTheme.DYNAMIC,
                        onClick = { onSetColorTheme(ColorTheme.DYNAMIC) }
                    )
                    ThemeOption(
                        title = "Dark",
                        subtitle = "Тёмная тема",
                        selected = colorTheme == ColorTheme.DARK,
                        onClick = { onSetColorTheme(ColorTheme.DARK) }
                    )
                    ThemeOption(
                        title = "Light",
                        subtitle = "Светлая тема",
                        selected = colorTheme == ColorTheme.LIGHT,
                        onClick = { onSetColorTheme(ColorTheme.LIGHT) }
                    )
                }

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Автосинхронизация WebDAV",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Загружать токены при запуске",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSyncWebDav,
                        onCheckedChange = onToggleAutoSync
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Закрыть")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
fun ThemeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}

@Composable
fun TokenResultCard(item: TokenStatus, onToggleExpand: () -> Unit, modifier: Modifier = Modifier) {
    val result = when (item.status) {
        is TokenCheckResult.Valid -> {
            val valid = item.status as TokenCheckResult.Valid
            Triple(
                Icons.Default.CheckCircle,
                MaterialTheme.colorScheme.primaryContainer,
                "${valid.name} (id${valid.id})"
            )
        }
        is TokenCheckResult.Invalid -> Triple(
            Icons.Default.Cancel,
            MaterialTheme.colorScheme.errorContainer,
            item.status.reason
        )
        is TokenCheckResult.Error -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.tertiaryContainer,
            item.status.reason
        )
    }
    val (icon, containerColor, statusText) = result
    val contentColor = when (item.status) {
        is TokenCheckResult.Valid -> MaterialTheme.colorScheme.onPrimaryContainer
        is TokenCheckResult.Invalid -> MaterialTheme.colorScheme.onErrorContainer
        is TokenCheckResult.Error -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(color = containerColor, shape = MaterialTheme.shapes.small)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = 1
                    )
                    Text(
                        text = "${item.token.take(24)}${if (item.token.length > 24) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 1.dp),
                        maxLines = 1
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (item.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (item.expanded) "Свернуть" else "Развернуть",
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (item.expanded) {
                Divider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.shapes.small
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = item.token,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun MainContent(viewModel: MainViewModel) {
    TokenCheckerApp(viewModel)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val colorTheme by viewModel.colorTheme.collectAsState()
            // Автосинхронизация при запуске
            viewModel.syncWebDavOnStartup()
            VKTokenCheckerTheme(colorTheme = colorTheme) {
                MainContent(viewModel)
            }
        }
    }
}

@Composable
fun VKTokenCheckerTheme(
    colorTheme: ColorTheme = ColorTheme.DYNAMIC,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = colorTheme != ColorTheme.LIGHT
    
    val colorScheme = when {
        // Dynamic Color - системная палитра
        colorTheme == ColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // Dark Theme - приглушённая сине-серая палитра Material 3
        colorTheme == ColorTheme.DARK -> darkColorScheme(
            primary = Color(0xFFA8C7FA),
            onPrimary = Color(0xFF002F6C),
            primaryContainer = Color(0xFF2B4A84),
            onPrimaryContainer = Color(0xFFD8E3FF),
            secondary = Color(0xFFBDC7DC),
            onSecondary = Color(0xFF273141),
            secondaryContainer = Color(0xFF3E4758),
            onSecondaryContainer = Color(0xFFD9E3F8),
            tertiary = Color(0xFFDCBCE1),
            onTertiary = Color(0xFF3E2845),
            tertiaryContainer = Color(0xFF563E5C),
            onTertiaryContainer = Color(0xFFF9D8FE),
            background = Color(0xFF1A1C1E),
            onBackground = Color(0xFFE2E2E6),
            surface = Color(0xFF1A1C1E),
            onSurface = Color(0xFFE2E2E6),
            surfaceVariant = Color(0xFF43474E),
            onSurfaceVariant = Color(0xFFC4C6CF),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6)
        )
        // Light Theme - светлая палитра Material 3
        else -> lightColorScheme(
            primary = Color(0xFF3561AC),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD8E3FF),
            onPrimaryContainer = Color(0xFF001D3F),
            secondary = Color(0xFF575E71),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDBE2F9),
            onSecondaryContainer = Color(0xFF141B2C),
            tertiary = Color(0xFF715573),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFCD7FC),
            onTertiaryContainer = Color(0xFF2A132D),
            background = Color(0xFFFEFBFF),
            onBackground = Color(0xFF1B1B1F),
            surface = Color(0xFFFEFBFF),
            onSurface = Color(0xFF1B1B1F),
            surfaceVariant = Color(0xFFE1E2EC),
            onSurfaceVariant = Color(0xFF44474F),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
