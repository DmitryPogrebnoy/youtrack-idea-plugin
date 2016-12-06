package com.github.jk1.ytplugin.editor

import com.github.jk1.ytplugin.ComponentAware
import com.github.jk1.ytplugin.logger
import com.github.jk1.ytplugin.rest.AdminRestClient
import com.intellij.concurrency.JobScheduler
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.github.jk1.ytplugin.editor.IssueNavigationLinkFactory.YouTrackIssueNavigationLink
import com.github.jk1.ytplugin.editor.IssueNavigationLinkFactory.createdByYouTrackPlugin
import com.github.jk1.ytplugin.editor.IssueNavigationLinkFactory.setProjects
import com.github.jk1.ytplugin.editor.IssueNavigationLinkFactory.pointsTo
import com.github.jk1.ytplugin.tasks.YouTrackServer
import com.intellij.openapi.vcs.IssueNavigationLink
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AdminComponent(override val project: Project) : AbstractProjectComponent(project), ComponentAware {

    private val restClient = AdminRestClient(project)
    private lateinit var projectListRefreshTask: ScheduledFuture<*>

    fun getActiveTaskVisibilityGroups(): List<String> {
        val repo = taskManagerComponent.getActiveYouTrackRepository()
        val taskId = taskManagerComponent.getActiveYouTrackTask().id
        return restClient.getVisibilityGroups(repo, taskId)
    }

    override fun projectOpened() {
        // update navigation links every 30 min to recognize new projects
        projectListRefreshTask = JobScheduler.getScheduler().scheduleWithFixedDelay({
            updateNavigationLinkPatterns()
        }, 1, 60, TimeUnit.MINUTES)
        // update navigation links when server connection configuration has been changed
        taskManagerComponent.addConfigurationChangeListener { updateNavigationLinkPatterns() }
    }

    override fun projectClosed() {
        projectListRefreshTask.cancel(false)
    }

    private fun updateNavigationLinkPatterns() {
        val navigationConfig = IssueNavigationConfiguration.getInstance(project)
        navigationConfig.links.remove(null) // where are these nulls coming from I wonder
        taskManagerComponent.getAllConfiguredYouTrackRepositories().forEach { server ->
            val links = navigationConfig.links.filter { it.pointsTo(server) }
            val generatedLinks = links.filter { it.createdByYouTrackPlugin }
            if (links.isEmpty()) {
                // no issue links to that server have been defined so far
                val link = YouTrackIssueNavigationLink(server.url)
                updateIssueLinkProjects(link, server)
                navigationConfig.links.add(link)
            } else if (generatedLinks.isNotEmpty()) {
                // there is a link created by plugin, let's actualize it
                updateIssueLinkProjects(generatedLinks.first(), server)
            } else {
                logger.debug("Issue navigation link pattern for ${server.url} has been overridden and won't be updated")
            }
        }
    }

    private fun updateIssueLinkProjects(link: IssueNavigationLink, repo: YouTrackServer) {
        try {
            repo.login()
            val projects = restClient.getAccessibleProjects(repo)
            if (projects.isEmpty()) {
                logger.debug("No accessible projects found for ${repo.url}")
            } else {
                link.setProjects(projects)
            }
        } catch(e: Exception) {
            logger.info(e)
        }
    }
}
