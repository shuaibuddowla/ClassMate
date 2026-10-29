package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.shuaib.classmate.R

class ClassMateBatchActivity : AppCompatActivity() {

    private lateinit var friendsScreen: ClassMateFriendsScreen
    private var batchId: String = ""
    private var batchLabel: String = ""
    private var isTeacher: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lightTheme = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }

        batchId = intent.getStringExtra("EXTRA_BATCH_ID") ?: ""
        batchLabel = intent.getStringExtra("EXTRA_BATCH_LABEL") ?: "Batch"
        val role = intent.getStringExtra("EXTRA_ROLE") ?: ""
        isTeacher = role == "teacher"

        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cm_background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBars.top + (4 * density).toInt(), 0, navBars.bottom)
            insets
        }

        friendsScreen = ClassMateFriendsScreen(
            this,
            lifecycleScope,
            { batchId },
            { phone ->
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open phone dialer", Toast.LENGTH_SHORT).show()
                }
            },
            { isTeacher },
            onBack = { finish() }
        )

        friendsScreen.render(container, batchLabel)
    }
}
