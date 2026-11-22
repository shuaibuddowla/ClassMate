package com.shuaib.classmate.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.repositories.AssessmentRepository
import com.shuaib.classmate.ui.assessment.CourseChoice
import com.shuaib.classmate.ui.assessment.UpcomingAssessmentsScreen
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.NotificationSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class UpcomingAssessmentsFragment : Fragment() {

    private lateinit var assessmentRepository: AssessmentRepository
    private var pendingTargetAssessmentId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_upcoming, container, false)
        assessmentRepository = AssessmentRepository.getInstance(requireContext())
        val batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }
        assessmentRepository.loadAssessments(batchId)

        val swipeRefresh = root.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshUpcoming)
        swipeRefresh?.setColorSchemeResources(R.color.cm_primary)
        swipeRefresh?.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                assessmentRepository.refreshFromRemote(batchId)
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        val composeView = root.findViewById<ComposeView>(R.id.composeViewUpcoming)
        val canManage = AppContextManager.getRole() in setOf("admin", "teacher", "cr")

        // Setup Compose Screen
        composeView.setContent {
            UpcomingAssessmentsScreen(
                repository = assessmentRepository,
                currentBatchId = batchId,
                canManage = canManage,
                availableCourses = emptyList(),
                routineLookup = { _, _ -> null },
                onPostNotice = { _, _, _, _ -> },
                onBroadcastPush = { title, msg ->
                    NotificationSender.sendToBatch(
                        batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
                        title = title,
                        message = msg,
                        type = "assignment"
                    )
                },
                courseFilesLookup = { emptyList() },
                onOpenFile = {},
                onIsAtTopChanged = { isTop ->
                    swipeRefresh?.isEnabled = isTop
                },
                targetAssessmentId = pendingTargetAssessmentId,
                onClearTargetAssessment = { pendingTargetAssessmentId = null }
            )
        }

        return root
    }

    fun openAssessment(assessmentId: String) {
        pendingTargetAssessmentId = assessmentId
    }
}
