package com.github.jk1.ytplugin.search

import com.github.jk1.ytplugin.search.actions.CreateIssueAction
import com.github.jk1.ytplugin.search.actions.RefreshIssuesAction
import com.github.jk1.ytplugin.search.actions.SetAsActiveTaskAction
import com.github.jk1.ytplugin.search.model.Issue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

class IssueViewer(val project: Project, parent: Disposable) : JBLoadingPanel(BorderLayout(), parent), DataProvider, Disposable {

    var myList: JBList = JBList()

    init {
        myList.fixedCellHeight = 80
        myList.cellRenderer = IssueListCellRenderer()
        myList.model = CollectionListModel<Issue>()

        val splitter = EditorSplitter(project)

        val browser = MessageBrowser(project)

        val scrollPane = JBScrollPane(myList, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
        scrollPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                myList.fixedCellWidth = scrollPane.visibleRect.width - 30
            }
        })

        splitter.firstComponent = scrollPane
        splitter.secondComponent = browser

        add(splitter, BorderLayout.CENTER)

        startLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            val model = Ref.create(myList.model)
            try {
                model.set(IssuesModel(project))
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    myList.model = model.get()
                    stopLoading()
                }
            }
        }
        val group = DefaultActionGroup()
        group.add(RefreshIssuesAction())
        group.add(CreateIssueAction())
        group.add(SetAsActiveTaskAction())
        add(ActionManager.getInstance()
                .createActionToolbar("Actions", group, false)
                .component, BorderLayout.WEST)

    }

    override fun getData(dataId: String): Any? {
        if (PlatformDataKeys.PROJECT.equals(dataId)) {
            return project
        }
        if (DataKey.create<Array<Issue>>("MYY_ISSUES_ARRAY").equals(dataId)) {
            val values = myList.selectedValuesList
            val issues = arrayOfNulls<Issue>(values.size)
            for (i in values.indices) {
                issues[i] = values[i] as Issue
            }
            return issues
        }

        return null
    }

    override fun dispose() {

    }
}