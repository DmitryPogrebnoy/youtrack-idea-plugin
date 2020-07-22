package com.github.jk1.ytplugin.setupWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.tasks.LocalTask
import com.intellij.tasks.Task
import com.intellij.tasks.TaskListener
import com.intellij.tasks.impl.LocalTaskImpl
import com.intellij.tasks.youtrack.YouTrackRepository
import com.intellij.util.containers.ContainerUtil

/**
 * @author Dmitry Avdeev
 */
abstract class SetupManager {
    enum class VcsOperation {
        CREATE_BRANCH,
        CREATE_CHANGELIST,
        DO_NOTHING
    }


    abstract fun getIssues(query: String?): List<Task>

    abstract fun getIssues(query: String?, forceRequest: Boolean): List<Task>

    abstract fun getIssues(query: String?,
                  offset: Int,
                  limit: Int,
                  withClosed: Boolean,
                  indicator: ProgressIndicator,
                  forceRequest: Boolean): List<Task>

    abstract fun getCachedIssues(): List<Task>

    abstract fun getCachedIssues(withClosed: Boolean): List<Task>

    abstract fun getLocalTasks(): MutableList<LocalTask?>

    abstract fun getLocalTasks(withClosed: Boolean): MutableList<LocalTask?>

    abstract fun addTask(issue: Task): LocalTask

    abstract fun createLocalTask(summary: String): LocalTaskImpl

    abstract fun activateTask(task: Task, clearContext: Boolean): LocalTask

    abstract fun getActiveTask(): LocalTask

    abstract fun findTask(id: String): LocalTask?

    abstract fun updateIssue(id: String)

    abstract fun isVcsEnabled(): Boolean

    abstract fun getActiveVcs(): AbstractVcs?

    abstract fun isLocallyClosed(localTask: LocalTask): Boolean

    abstract fun getAssociatedTask(list: LocalChangeList): LocalTask?

    abstract fun trackContext(changeList: LocalChangeList)

    abstract fun removeTask(task: LocalTask)

    abstract fun addTaskListener(listener: TaskListener)

    abstract fun addTaskListener(listener: TaskListener, parentDisposable: Disposable)

    abstract fun removeTaskListener(listener: TaskListener)

    abstract fun getAllRepositories(): Array<YouTrackRepository>

    abstract fun testConnection(repository: YouTrackRepository): Boolean

    companion object {
        fun getManager(project: Project): SetupManager {
            return project.getService(SetupManager::class.java)
        }
    }
}