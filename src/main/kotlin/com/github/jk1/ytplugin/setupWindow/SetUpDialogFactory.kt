// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.github.jk1.ytplugin.setupWindow

import com.github.jk1.ytplugin.ComponentAware
import com.github.jk1.ytplugin.issues.model.Issue
import com.github.jk1.ytplugin.logger
import com.github.jk1.ytplugin.rest.IssuesRestClient
import com.github.jk1.ytplugin.tasks.IssueTask
import com.github.jk1.ytplugin.tasks.NoActiveYouTrackTaskException
import com.github.jk1.ytplugin.ui.IssueListToolWindowContent
import com.github.jk1.ytplugin.ui.YouTrackPluginIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities


/**
 * Create the tool window content
 * @author Alina Boshchenko
 */
class SetUpDialogFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        createContent(project, toolWindow)
        // listen to task management plugin configuration changes and update tool window accordingly
        ComponentAware.of(project).taskManagerComponent.addConfigurationChangeListener {
            SwingUtilities.invokeLater {
                logger.debug("Server configuration change detected, reloading tool window contents")
                createContent(project, toolWindow)
            }
        }
        // listen to resize events and convert from horizontal to vertical layout and back
        toolWindow.component.addComponentListener(object : ComponentAdapter() {

            private var horizontal = toolWindow.anchor.isHorizontal

            override fun componentResized(e: ComponentEvent) {
                if ((e.component.width > e.component.height).xor(horizontal)) {
                    horizontal = !horizontal
                    createContent(project, toolWindow)
                }
            }
        })
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(YouTrackPluginIcons.YOUTRACK_TOOL_WINDOW) // loaded via IconLoader, thus adaptive
    }

    private fun createContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        contentManager.removeAllContents(true)
        val repos = ComponentAware.of(project).taskManagerComponent.getAllConfiguredYouTrackRepositories()
        logger.debug("${repos.size} YouTrack repositories discovered")
        when {
            repos.isEmpty() -> contentManager.addContent("", createPlaceholderPanel(project))
            else -> {
                repos.forEach {
                    val panel = IssueListToolWindowContent(!toolWindow.anchor.isHorizontal, it)
                    contentManager.addContent("Issues | ${it.url.split("//").last()}", panel)
                }
                Disposer.register(project, Disposable {
                    contentManager.removeAllContents(true)
                })
            }
        }
    }

    private fun ContentManager.addContent(title: String, component: JComponent) {
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory.createContent(component, title, false)
        content.isCloseable = false
        addContent(content)
    }

    private fun getIssueFromCurrentActiveTask(project: Project): Issue {
        return ComponentAware.of(project) {
            val task = taskManagerComponent.getActiveYouTrackTask()
            if (task is IssueTask) {
                task.issue
            } else {
                // try local store first, fall back to rest api if not found
                val repo = taskManagerComponent.getActiveYouTrackRepository()
                issueStoreComponent[repo].firstOrNull { it.id == task.id }
                        ?: IssuesRestClient(repo).getIssue(task.id)
                        ?: throw NoActiveYouTrackTaskException()
            }
        }
    }

    private fun createPlaceholderPanel(project: Project): JComponent {
        val panel = JPanel(BorderLayout())
        val labelContainer = JPanel()
        val messageLabel = JLabel("No YouTrack server found")
        val configureLabel = createLink("Configure") { SetupDialog(project).show() }

        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        configureLabel.alignmentX = Component.CENTER_ALIGNMENT
        labelContainer.add(messageLabel)
        labelContainer.add(configureLabel)
        panel.add(labelContainer, BorderLayout.NORTH)
        return panel
    }

    private fun createLink(text: String, onClick: () -> Unit): JComponent {
        val label = SimpleColoredComponent()
        label.append(text, SimpleTextAttributes.LINK_ATTRIBUTES)
        label.cursor = Cursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick.invoke()
            }
        })
        return label
    }

}
