package com.example.sriedu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

// Data Models
data class Paper(
    val id: String,
    val title: String,
    val subject: String,
    val grade: String,
    val type: String,
    val category: String,
    val fileUrl: String,
    val downloadCount: Int,
    val uploadDate: Date,
    val tags: List<String>
)

data class User(
    val id: String,
    val email: String,
    val name: String,
    val grade: String,
    val favorites: List<String> = emptyList(),
    val downloads: List<String> = emptyList()
)

// Enhanced Authentication ViewModel with Better Duplicate Prevention
class AuthViewModel : ViewModel() {
    private val auth: FirebaseAuth = Firebase.auth
    private val firestore: FirebaseFirestore = Firebase.firestore

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _user = mutableStateOf<User?>(null)
    val user: State<User?> = _user

    private val _isLoggedIn = mutableStateOf(false)
    val isLoggedIn: State<Boolean> = _isLoggedIn

    private val _authError = mutableStateOf<String?>(null)
    val authError: State<String?> = _authError

    private val _authSuccess = mutableStateOf<String?>(null)
    val authSuccess: State<String?> = _authSuccess

    init {
        checkAuthState()
    }

    private fun checkAuthState() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            _isLoggedIn.value = true
            loadUserData(currentUser.uid)
        }
    }

    private fun loadUserData(userId: String) {
        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val userData = document.data
                    _user.value = User(
                        id = userId,
                        email = userData?.get("email") as? String ?: "",
                        name = userData?.get("name") as? String ?: "",
                        grade = userData?.get("grade") as? String ?: "",
                        favorites = userData?.get("favorites") as? List<String> ?: emptyList(),
                        downloads = userData?.get("downloads") as? List<String> ?: emptyList()
                    )
                }
            }
    }

    // Public methods to set error and success messages
    fun setAuthError(message: String) {
        _authError.value = message
    }

    fun setAuthSuccess(message: String) {
        _authSuccess.value = message
    }

    fun clearMessages() {
        _authError.value = null
        _authSuccess.value = null
    }

    // Enhanced signUp method with better duplicate prevention
    suspend fun signUp(email: String, password: String, name: String, grade: String): Result<String> {
        return try {
            _isLoading.value = true
            _authError.value = null
            _authSuccess.value = null

            // Clean and validate email first
            val cleanEmail = email.trim().lowercase()

            // Double-check if user already exists before attempting to create
            try {
                val signInMethods = auth.fetchSignInMethodsForEmail(cleanEmail).await()
                if (signInMethods.signInMethods?.isNotEmpty() == true) {
                    _isLoading.value = false
                    val errorMessage = "📧 An account with this email already exists. Please try signing in instead."
                    _authError.value = errorMessage
                    return Result.failure(Exception(errorMessage))
                }
            } catch (fetchError: Exception) {
                // If fetch fails, continue with creation attempt
                // This ensures we don't block legitimate signups due to network issues
            }

            val result = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val userId = result.user?.uid ?: throw Exception("User creation failed")

            // Save user data to Firestore
            val userData = hashMapOf(
                "email" to cleanEmail,
                "name" to name.trim(),
                "grade" to grade.trim(),
                "favorites" to emptyList<String>(),
                "downloads" to emptyList<String>(),
                "createdAt" to System.currentTimeMillis()
            )

            firestore.collection("users").document(userId).set(userData).await()

            _isLoggedIn.value = true
            loadUserData(userId)
            _isLoading.value = false

            val successMessage = "🎉 Welcome to EduPapers LK, ${name.trim()}! Your account has been created successfully."
            _authSuccess.value = successMessage
            Result.success(successMessage)

        } catch (e: Exception) {
            _isLoading.value = false
            val errorMessage = when (e) {
                is FirebaseAuthUserCollisionException -> {
                    "📧 An account with this email already exists. Please try signing in instead."
                }
                is FirebaseAuthWeakPasswordException -> {
                    "🔒 Password is too weak. Please use at least 6 characters with a mix of letters and numbers."
                }
                is FirebaseAuthInvalidCredentialsException -> {
                    "📧 Invalid email format. Please enter a valid email address."
                }
                else -> {
                    "❌ Account creation failed: ${e.message ?: "Unknown error occurred"}"
                }
            }
            _authError.value = errorMessage
            Result.failure(Exception(errorMessage))
        }
    }

    // Enhanced signIn method
    suspend fun signIn(email: String, password: String): Result<String> {
        return try {
            _isLoading.value = true
            _authError.value = null
            _authSuccess.value = null

            // Clean email
            val cleanEmail = email.trim().lowercase()

            val result = auth.signInWithEmailAndPassword(cleanEmail, password).await()
            val user = result.user

            if (user != null) {
                _isLoggedIn.value = true
                loadUserData(user.uid)
                _isLoading.value = false

                val successMessage = "✅ Welcome back! You've successfully signed in."
                _authSuccess.value = successMessage
                Result.success(successMessage)
            } else {
                throw Exception("Sign in failed")
            }

        } catch (e: Exception) {
            _isLoading.value = false
            val errorMessage = when (e) {
                is FirebaseAuthInvalidUserException -> {
                    "👤 No account found with this email address. Please check your email or create a new account."
                }
                is FirebaseAuthInvalidCredentialsException -> {
                    "🔑 Incorrect password or email format. Please check your credentials and try again."
                }
                else -> {
                    "❌ Sign in failed: ${e.message ?: "Please check your internet connection and try again"}"
                }
            }
            _authError.value = errorMessage
            Result.failure(Exception(errorMessage))
        }
    }

    // Enhanced resetPassword method
    suspend fun resetPassword(email: String): Result<String> {
        return try {
            _isLoading.value = true
            _authError.value = null
            _authSuccess.value = null

            // Clean email
            val cleanEmail = email.trim().lowercase()

            auth.sendPasswordResetEmail(cleanEmail).await()
            _isLoading.value = false

            val successMessage = "📧 Password reset email sent! Please check your inbox and follow the instructions."
            _authSuccess.value = successMessage
            Result.success(successMessage)

        } catch (e: Exception) {
            _isLoading.value = false
            val errorMessage = when (e) {
                is FirebaseAuthInvalidUserException -> {
                    "👤 No account found with this email address. Please check your email or create a new account."
                }
                else -> {
                    "❌ Failed to send reset email: ${e.message ?: "Unknown error occurred"}"
                }
            }
            _authError.value = errorMessage
            Result.failure(Exception(errorMessage))
        }
    }

    fun signOut() {
        auth.signOut()
        _isLoggedIn.value = false
        _user.value = null
        _authError.value = null
        _authSuccess.value = null
    }
}

// Papers ViewModel (unchanged)
class EduPapersViewModel : ViewModel() {
    private val _papers = mutableStateOf<List<Paper>>(emptyList())
    val papers: State<List<Paper>> = _papers

    private val _selectedCategory = mutableStateOf("grades")
    val selectedCategory: State<String> = _selectedCategory

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _favorites = mutableStateOf<Set<String>>(emptySet())
    val favorites: State<Set<String>> = _favorites

    private val _downloads = mutableStateOf<Set<String>>(emptySet())
    val downloads: State<Set<String>> = _downloads

    private val _currentTab = mutableStateOf(0)
    val currentTab: State<Int> = _currentTab

    init {
        loadMockData()
    }

    private fun loadMockData() {
        val mockPapers = listOf(
            Paper(
                id = "1",
                title = "Grade 1 Mathematics - Basic Numbers",
                subject = "Mathematics",
                grade = "1",
                type = "Model Paper",
                category = "grades",
                fileUrl = "https://example.com/grade1-math.pdf",
                downloadCount = 850,
                uploadDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time,
                tags = listOf("numbers", "counting")
            ),
            Paper(
                id = "2",
                title = "Grade 5 Science - Living Things",
                subject = "Science",
                grade = "5",
                type = "Past Paper",
                category = "grades",
                fileUrl = "https://example.com/grade5-science.pdf",
                downloadCount = 1200,
                uploadDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -3) }.time,
                tags = listOf("biology", "plants", "animals")
            ),
            Paper(
                id = "3",
                title = "O/L Mathematics - 2023 Past Paper",
                subject = "Mathematics",
                grade = "O/L",
                type = "Past Paper",
                category = "olevel",
                fileUrl = "https://example.com/ol-math-2023.pdf",
                downloadCount = 3200,
                uploadDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -5) }.time,
                tags = listOf("algebra", "geometry")
            ),
            Paper(
                id = "4",
                title = "A/L Physics - 2023 Past Paper",
                subject = "Physics",
                grade = "A/L",
                type = "Past Paper",
                category = "alevel",
                fileUrl = "https://example.com/al-physics-2023.pdf",
                downloadCount = 2100,
                uploadDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -4) }.time,
                tags = listOf("mechanics", "electricity")
            ),
            Paper(
                id = "5",
                title = "Engineering Mathematics - Semester 1",
                subject = "Mathematics",
                grade = "University",
                type = "Past Paper",
                category = "university",
                fileUrl = "https://example.com/eng-math-sem1.pdf",
                downloadCount = 1200,
                uploadDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -8) }.time,
                tags = listOf("calculus", "linear algebra")
            )
        )
        _papers.value = mockPapers
    }

    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(paperId: String) {
        val currentFavorites = _favorites.value.toMutableSet()
        if (currentFavorites.contains(paperId)) {
            currentFavorites.remove(paperId)
        } else {
            currentFavorites.add(paperId)
        }
        _favorites.value = currentFavorites
    }

    fun addToDownloads(paperId: String) {
        val currentDownloads = _downloads.value.toMutableSet()
        currentDownloads.add(paperId)
        _downloads.value = currentDownloads
    }

    fun setCurrentTab(tab: Int) {
        _currentTab.value = tab
    }

    fun getFilteredPapers(): List<Paper> {
        return papers.value.filter { paper ->
            val matchesCategory = paper.category == selectedCategory.value
            val matchesSearch = searchQuery.value.isEmpty() ||
                    paper.title.contains(searchQuery.value, ignoreCase = true) ||
                    paper.subject.contains(searchQuery.value, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    fun getFavoritePapers(): List<Paper> {
        return papers.value.filter { favorites.value.contains(it.id) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EduPapersLKTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun EduPapersLKTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF3B82F6),
        secondary = Color(0xFF10B981),
        background = Color(0xFFF8FAFC),
        surface = Color.White
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val isLoggedIn by authViewModel.isLoggedIn

    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) "main" else "login"
    ) {
        composable("login") {
            LoginScreen(
                navController = navController,
                authViewModel = authViewModel
            )
        }
        composable("signup") {
            SignUpScreen(
                navController = navController,
                authViewModel = authViewModel
            )
        }
        composable("forgot_password") {
            ForgotPasswordScreen(
                navController = navController,
                authViewModel = authViewModel
            )
        }
        composable("main") {
            EduPapersApp(authViewModel = authViewModel)
        }
    }
}

// Enhanced Message Card Component
@Composable
fun MessageCard(
    message: String,
    isError: Boolean = false,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) Color(0xFFDC2626) else Color(0xFF059669),
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = if (isError) Color(0xFFDC2626) else Color(0xFF059669)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = if (isError) Color(0xFFDC2626) else Color(0xFF059669),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    authViewModel: AuthViewModel
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val isLoading by authViewModel.isLoading
    val authError by authViewModel.authError
    val authSuccess by authViewModel.authSuccess
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(authViewModel.isLoggedIn.value) {
        if (authViewModel.isLoggedIn.value) {
            navController.navigate("main") {
                popUpTo("login") { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF3B82F6), Color(0xFF10B981))
                )
            )
    ) {
        // Messages at the top
        authError?.let { error ->
            MessageCard(
                message = error,
                isError = true,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        authSuccess?.let { success ->
            MessageCard(
                message = success,
                isError = false,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo and Title
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.School,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "EduPapers LK",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                "Your Gateway to Academic Excellence",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Login Form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Welcome Back",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )

                    Text(
                        "Sign in to continue your learning journey",
                        fontSize = 14.sp,
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            authViewModel.clearMessages()
                        },
                        label = { Text("Email Address") },
                        placeholder = { Text("Enter your email") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            authViewModel.clearMessages()
                        },
                        label = { Text("Password") },
                        placeholder = { Text("Enter your password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Forgot Password Link
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { navController.navigate("forgot_password") }
                        ) {
                            Text(
                                "Forgot Password?",
                                fontSize = 12.sp,
                                color = Color(0xFF3B82F6)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            when {
                                email.isBlank() -> {
                                    authViewModel.setAuthError("📧 Please enter your email address")
                                }
                                password.isBlank() -> {
                                    authViewModel.setAuthError("🔑 Please enter your password")
                                }
                                !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> {
                                    authViewModel.setAuthError("📧 Please enter a valid email address")
                                }
                                else -> {
                                    coroutineScope.launch {
                                        authViewModel.signIn(email, password)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("Signing In...")
                            }
                        } else {
                            Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Divider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Divider(modifier = Modifier.weight(1f))
                        Text(
                            "  OR  ",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Divider(modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Sign Up Link
                    Row(
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Don't have an account? ",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Text(
                            "Sign Up",
                            fontSize = 14.sp,
                            color = Color(0xFF3B82F6),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                navController.navigate("signup")
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    navController: NavController,
    authViewModel: AuthViewModel
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    val isLoading by authViewModel.isLoading
    val authError by authViewModel.authError
    val authSuccess by authViewModel.authSuccess
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(authViewModel.isLoggedIn.value) {
        if (authViewModel.isLoggedIn.value) {
            navController.navigate("main") {
                popUpTo("signup") { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF3B82F6), Color(0xFF10B981))
                )
            )
    ) {
        // Messages at the top
        authError?.let { error ->
            MessageCard(
                message = error,
                isError = true,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        authSuccess?.let { success ->
            MessageCard(
                message = success,
                isError = false,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            item {
                // Logo and Title
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Join EduPapers LK",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "Start your academic journey with us",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Sign Up Form
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Create Your Account",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1F2937)
                        )

                        Text(
                            "Fill in your details to get started",
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                authViewModel.clearMessages()
                            },
                            label = { Text("Full Name") },
                            placeholder = { Text("Enter your full name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                authViewModel.clearMessages()
                            },
                            label = { Text("Email Address") },
                            placeholder = { Text("Enter your email") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = grade,
                            onValueChange = {
                                grade = it
                                authViewModel.clearMessages()
                            },
                            label = { Text("Grade/Level") },
                            placeholder = { Text("e.g., 10, O/L, A/L, University") },
                            leadingIcon = { Icon(Icons.Default.School, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                authViewModel.clearMessages()
                            },
                            label = { Text("Password") },
                            placeholder = { Text("Create a strong password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                authViewModel.clearMessages()
                            },
                            label = { Text("Confirm Password") },
                            placeholder = { Text("Re-enter your password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Icon(
                                        if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                when {
                                    name.isBlank() -> {
                                        authViewModel.setAuthError("👤 Please enter your full name")
                                    }
                                    email.isBlank() -> {
                                        authViewModel.setAuthError("📧 Please enter your email address")
                                    }
                                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> {
                                        authViewModel.setAuthError("📧 Please enter a valid email address")
                                    }
                                    grade.isBlank() -> {
                                        authViewModel.setAuthError("🎓 Please enter your grade or level")
                                    }
                                    password.isBlank() -> {
                                        authViewModel.setAuthError("🔑 Please create a password")
                                    }
                                    password.length < 6 -> {
                                        authViewModel.setAuthError("🔒 Password must be at least 6 characters long")
                                    }
                                    confirmPassword.isBlank() -> {
                                        authViewModel.setAuthError("🔑 Please confirm your password")
                                    }
                                    password != confirmPassword -> {
                                        authViewModel.setAuthError("🔑 Passwords don't match. Please check and try again")
                                    }
                                    else -> {
                                        coroutineScope.launch {
                                            authViewModel.signUp(email, password, name, grade)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            if (isLoading) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("Creating Account...")
                                }
                            } else {
                                Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Divider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Divider(modifier = Modifier.weight(1f))
                            Text(
                                "  OR  ",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Divider(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Sign In Link
                        Row(
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "Already have an account? ",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                            Text(
                                "Sign In",
                                fontSize = 14.sp,
                                color = Color(0xFF3B82F6),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    navController.navigate("login")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    navController: NavController,
    authViewModel: AuthViewModel
) {
    var email by remember { mutableStateOf("") }
    val isLoading by authViewModel.isLoading
    val authError by authViewModel.authError
    val authSuccess by authViewModel.authSuccess
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF3B82F6), Color(0xFF10B981))
                )
            )
    ) {
        // Messages at the top
        authError?.let { error ->
            MessageCard(
                message = error,
                isError = true,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        authSuccess?.let { success ->
            MessageCard(
                message = success,
                isError = false,
                onDismiss = { authViewModel.clearMessages() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo and Title
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Reset Password",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                "Enter your email to receive reset instructions",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Reset Form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Forgot Your Password?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )

                    Text(
                        "No worries! We'll send you reset instructions",
                        fontSize = 14.sp,
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            authViewModel.clearMessages()
                        },
                        label = { Text("Email Address") },
                        placeholder = { Text("Enter your registered email") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            when {
                                email.isBlank() -> {
                                    authViewModel.setAuthError("📧 Please enter your email address")
                                }
                                !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> {
                                    authViewModel.setAuthError("📧 Please enter a valid email address")
                                }
                                else -> {
                                    coroutineScope.launch {
                                        authViewModel.resetPassword(email)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("Sending...")
                            }
                        } else {
                            Text("Send Reset Email", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Back to Sign In
                    Row(
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Remember your password? ",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Text(
                            "Sign In",
                            fontSize = 14.sp,
                            color = Color(0xFF3B82F6),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                navController.navigate("login") {
                                    popUpTo("forgot_password") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// Rest of the components remain the same...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EduPapersApp(
    authViewModel: AuthViewModel,
    viewModel: EduPapersViewModel = viewModel()
) {
    val currentTab by viewModel.currentTab
    val user by authViewModel.user

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF3B82F6), Color(0xFF10B981))
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "EduPapers LK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            user?.let {
                                Text(
                                    "Welcome, ${it.name}",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { authViewModel.signOut() }) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign Out")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (currentTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Home") },
                    selected = currentTab == 0,
                    onClick = { viewModel.setCurrentTab(0) }
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (currentTab == 1) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Favorites"
                        )
                    },
                    label = { Text("Favorites") },
                    selected = currentTab == 1,
                    onClick = { viewModel.setCurrentTab(1) }
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (currentTab == 2) Icons.Filled.Person else Icons.Outlined.Person,
                            contentDescription = "Profile"
                        )
                    },
                    label = { Text("Profile") },
                    selected = currentTab == 2,
                    onClick = { viewModel.setCurrentTab(2) }
                )
            }
        }
    ) { paddingValues ->
        when (currentTab) {
            0 -> HomeScreen(viewModel, paddingValues)
            1 -> FavoritesScreen(viewModel, paddingValues)
            2 -> ProfileScreen(viewModel, paddingValues, authViewModel)
        }
    }
}

@Composable
fun HomeScreen(viewModel: EduPapersViewModel, paddingValues: PaddingValues) {
    val searchQuery by viewModel.searchQuery
    val selectedCategory by viewModel.selectedCategory
    val filteredPapers = viewModel.getFilteredPapers()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder = { Text("Search papers...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        )

        // Category Tabs
        LazyRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val categories = listOf(
                "grades" to "Grade 1-11",
                "olevel" to "O Level",
                "alevel" to "A Level",
                "university" to "University"
            )

            items(categories) { (id, title) ->
                FilterChip(
                    onClick = { viewModel.setSelectedCategory(id) },
                    label = { Text(title) },
                    selected = selectedCategory == id
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Papers List
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredPapers) { paper ->
                PaperCard(
                    paper = paper,
                    isFavorite = viewModel.favorites.value.contains(paper.id),
                    onFavoriteClick = { viewModel.toggleFavorite(paper.id) },
                    onDownloadClick = { viewModel.addToDownloads(paper.id) }
                )
            }
        }
    }
}

@Composable
fun FavoritesScreen(viewModel: EduPapersViewModel, paddingValues: PaddingValues) {
    val favoritePapers = viewModel.getFavoritePapers()

    if (favoritePapers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("No Favorites Yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Bookmark papers to add them here", color = Color.Gray)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(favoritePapers) { paper ->
                PaperCard(
                    paper = paper,
                    isFavorite = true,
                    onFavoriteClick = { viewModel.toggleFavorite(paper.id) },
                    onDownloadClick = { viewModel.addToDownloads(paper.id) }
                )
            }
        }
    }
}

@Composable
fun ProfileScreen(
    viewModel: EduPapersViewModel,
    paddingValues: PaddingValues,
    authViewModel: AuthViewModel
) {
    val downloads by viewModel.downloads
    val favorites by viewModel.favorites
    val user by authViewModel.user

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Profile Avatar
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF3B82F6), Color(0xFF10B981))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(50.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            user?.name ?: "Student User",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Grade ${user?.grade ?: "N/A"} Student",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Text(
            user?.email ?: "",
            fontSize = 14.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Statistics Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    "Statistics",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            downloads.size.toString(),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B82F6)
                        )
                        Text("Downloads", fontSize = 12.sp, color = Color.Gray)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            favorites.size.toString(),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Text("Favorites", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sign Out Button
        Button(
            onClick = { authViewModel.signOut() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
        ) {
            Icon(Icons.Default.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out")
        }
    }
}

@Composable
fun PaperCard(
    paper: Paper,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        paper.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0xFFF3F4F6),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                paper.subject,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                        }

                        Surface(
                            color = getTypeColor(paper.type).copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                paper.type,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                color = getTypeColor(paper.type)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Gray
                            )
                            Text(
                                "${paper.downloadCount}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onDownloadClick()
                            Toast.makeText(context, "📄 Paper downloaded successfully!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3B82F6)
                        )
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download")
                    }

                    IconButton(
                        onClick = {
                            onFavoriteClick()
                            Toast.makeText(
                                context,
                                if (isFavorite) "💔 Removed from favorites" else "❤️ Added to favorites",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            tint = if (isFavorite) Color(0xFF3B82F6) else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

fun getTypeColor(type: String): Color {
    return when (type) {
        "Past Paper" -> Color(0xFF3B82F6)
        "Model Paper" -> Color(0xFF10B981)
        "Test Paper" -> Color(0xFFF59E0B)
        else -> Color.Gray
    }
}