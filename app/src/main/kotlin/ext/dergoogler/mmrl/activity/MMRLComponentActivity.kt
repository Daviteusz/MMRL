package ext.dergoogler.mmrl.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dergoogler.mmrl.R
import com.dergoogler.mmrl.compat.PermissionCompat
import com.dergoogler.mmrl.repository.LocalRepository
import com.dergoogler.mmrl.repository.UserPreferencesRepository
import com.dergoogler.mmrl.ui.activity.CrashHandlerActivity
import com.dergoogler.mmrl.ui.activity.InstallActivity
import com.dergoogler.mmrl.ui.activity.ModConfActivity
import com.dergoogler.mmrl.ui.providable.LocalUserPreferences
import com.dergoogler.mmrl.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.dergoogler.mmrl.compat.BuildCompat
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.exitProcess

@AndroidEntryPoint
open class MMRLComponentActivity : ComponentActivity(), DefaultLifecycleObserver {
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var localRepository: LocalRepository

    open val requirePermissions = listOf<String>()
    var permissionsGranted = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super<ComponentActivity>.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            startCrashActivity(thread, throwable)
        }

        val granted = if (BuildCompat.atLeastT) {
            PermissionCompat.checkPermissions(
                this,
                requirePermissions
            ).allGranted
        } else {
            true
        }

        if (!granted) {
            PermissionCompat.requestPermissions(this, requirePermissions) { state ->
                permissionsGranted = state.allGranted
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun startCrashActivity(thread: Thread, throwable: Throwable) {
        val intent = Intent(this, CrashHandlerActivity::class.java).apply {
            putExtra("message", throwable.message)
            putExtra("stacktrace", formatStackTrace(throwable))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()

        exitProcess(0)
    }

    private fun formatStackTrace(throwable: Throwable, numberOfLines: Int = 88): String {
        val stackTrace = throwable.stackTrace
        val stackTraceElements = stackTrace.joinToString("\n") { it.toString() }

        return if (stackTrace.size > numberOfLines) {
            val trimmedStackTrace =
                stackTraceElements.lines().take(numberOfLines).joinToString("\n")
            val moreCount = stackTrace.size - numberOfLines

            getString(R.string.stack_trace_truncated, trimmedStackTrace, moreCount)
        } else {
            stackTraceElements
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        isAppInForeground = false
    }

    companion object {
        var isAppInForeground = false
        const val MODULE_UPDATE_WORK_NAME = "ModuleUpdateWork"
        const val REPO_UPDATE_WORK_NAME = "RepoUpdateWork"

        inline fun <reified W : ListenableWorker> startWorkTask(
            context: Context,
            enabled: Boolean,
            repeatInterval: Int,
            repeatIntervalUnit: TimeUnit = TimeUnit.HOURS,
            workName: String,
        ) {
            if (enabled) {
                val updateRequest = PeriodicWorkRequestBuilder<W>(
                    repeatInterval.toLong(),
                    repeatIntervalUnit
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        workName,
                        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                        updateRequest
                    )
            } else {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(workName)
            }
        }

        fun cancelWorkTask(context: Context, workName: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(workName)
        }

        fun startModConfActivity(context: Context, modId: String) {
            val intent = Intent(context, ModConfActivity::class.java)
                .apply {
                    putExtra("MOD_ID", modId)
                }

            context.startActivity(intent)
        }

        fun startInstallActivity(context: Context, uri: Uri) {
            val intent = Intent(context, InstallActivity::class.java)
                .apply {
                    data = uri
                }

            context.startActivity(intent)
        }
    }
}

fun MMRLComponentActivity.setBaseContent(
    parent: CompositionContext? = null,
    content: @Composable () -> Unit
) {
    this.setContent(
        parent = parent,
    ) {
        val userPreferences by userPreferencesRepository.data.collectAsStateWithLifecycle(
            initialValue = null
        )

        val preferences = if (userPreferences == null) {
            return@setContent
        } else {
            checkNotNull(userPreferences)
        }

        CompositionLocalProvider(
            LocalUserPreferences provides preferences
        ) {
            AppTheme(
                darkMode = preferences.isDarkMode(), themeColor = preferences.themeColor
            ) {
                content()
            }
        }
    }
}