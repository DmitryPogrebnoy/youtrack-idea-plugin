// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.github.jk1.ytplugin.issues

import com.github.jk1.ytplugin.*
import com.github.jk1.ytplugin.issues.model.Issue
import com.github.jk1.ytplugin.setupWindow.NotifierState
import com.github.jk1.ytplugin.setupWindow.SetupTask
import com.github.jk1.ytplugin.tasks.YouTrackServer
import com.intellij.openapi.project.Project
import com.intellij.tasks.youtrack.YouTrackRepository
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.jetbrains.rd.util.string.printToString
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset
import java.util.*
import javax.swing.JLabel
import kotlin.collections.ArrayList

class SetupVariationsTest : SetupManagerTrait, IdeaProjectTrait, SetupConnectionTrait, ComponentAware {

    private lateinit var fixture: IdeaProjectTestFixture
    override lateinit var repository: YouTrackServer
    override val project: Project by lazy { fixture.project }

    @Before
    fun setUp() {
        fixture = getLightCodeInsightFixture()
        fixture.setUp()
    }

    @Test
    fun `test if connected repository has issues that can be displayed`() {
        val serverUrl = "https://ytplugintest.myjetbrains.com/youtrack"
        val token = "perm:aWRlcGx1Z2lu.NjItMA==.7iaoaBCduVgrbAj9BkQSxksQLQcEte"
        repository = createYouTrackRepository(serverUrl, token, false, false, false, false)
        repository.defaultSearch = "Assignee:Unassigned"
        val repo = repository.getRepo()
        val setupTask = SetupTask()
        setupTask.testConnection(repo, project)
        issueStoreComponent[repository].update(repository).waitFor(5000)

        Assert.assertEquals(NotifierState.SUCCESS, setupTask.noteState)
        /* should contain on task (AT-10825 Test Task) */
        Assert.assertEquals(issueStoreComponent[repository].getAllIssues().lastIndex, 0)
    }

    @Test
    fun `test login anonymously feature`() {
        val serverUrl = "https://ytplugintest.myjetbrains.com/youtrack"
        repository = createYouTrackRepository(serverUrl, token, false, false, false, true)
        val repo = repository.getRepo()
        val setupTask = SetupTask()
        setupTask.testConnection(repo, project)
        Assert.assertEquals(200, setupTask.statusCode)
    }

    @Test
    fun `test login anonymously feature with invalid url`() {
        val serverUrl = "https://ytplugintest"
        repository = createYouTrackRepository(serverUrl, token, false, false, false, true)
        val repo = repository.getRepo()
        val setupTask = SetupTask()
        setupTask.testConnection(repo, project)
        Assert.assertEquals(NotifierState.UNKNOWN_HOST, setupTask.noteState)
        Assert.assertEquals(401, setupTask.statusCode)
    }

    @Test
    fun `test share url feature`() {
        val serverUrl = "https://ytplugintest.myjetbrains.com/youtrack"
        val token = "perm:aWRlcGx1Z2lu.NjItMA==.7iaoaBCduVgrbAj9BkQSxksQLQcEte"
        repository = createYouTrackRepository(serverUrl, token, true, false, false, false)
        val repo = repository.getRepo()
        val setupTask = SetupTask()
        setupTask.testConnection(repo, project)
        Assert.assertEquals(NotifierState.SUCCESS, setupTask.noteState)
        Assert.assertEquals(200, setupTask.statusCode)
    }

    @Test
    fun `test use HTTP feature`() {
        val serverUrl = "https://ytplugintest.myjetbrains.com/youtrack"
        val token = "perm:aWRlcGx1Z2lu.NjItMA==.7iaoaBCduVgrbAj9BkQSxksQLQcEte"
        repository = createYouTrackRepository(serverUrl, token, false, false, true, false)
        val repo = repository.getRepo()
        val setupTask = SetupTask()
        setupTask.testConnection(repo, project)
        Assert.assertEquals(NotifierState.SUCCESS, setupTask.noteState)
        Assert.assertEquals(200, setupTask.statusCode)
    }

    @After
    fun tearDown() {
        issueStoreComponent.remove(repository)
        cleanUpTaskManager()
        fixture.tearDown()
    }
}